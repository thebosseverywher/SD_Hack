package com.flow.app

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Disk-backed [Index] so Travis's ambient memory SURVIVES app restarts and reboots.
 *
 * Design: all search/get/count is delegated to an in-memory cosine [InMemoryIndex] (fast,
 * unchanged), and every successful ingest is additionally appended to an append-only JSON
 * Lines file in filesDir. On construction the file is replayed back into the in-memory store,
 * so the moment the app process starts, prior memory is already there (no empty-fallback,
 * the Ask gate is satisfied immediately if there's history).
 *
 * The file is compacted on startup when it has grown much larger than the live entry set
 * (Trail emits unique ids, so duplicates are rare, but upserts + long uptime can bloat it).
 *
 * Everything stays on-device and private — this is just local durable storage in the app's
 * own sandbox (filesDir), wiped on uninstall.
 */
class PersistentIndex(context: Context) : Index {

    /** One durable record: the item plus its (optional) text/image vectors. */
    @Serializable
    private data class Row(
        val item: Item,
        val textVec: List<Float>? = null,
        val imageVec: List<Float>? = null
    )

    private val mem = InMemoryIndex()
    private val file = File(context.filesDir, FILE_NAME)
    private val writeLock = Any()
    private var writer: BufferedWriter? = null

    init {
        val loaded = load()
        // Compact if the on-disk line count has drifted well past the live set.
        if (loaded > mem.count() * 2 + 256) compact()
        openWriter()
        Log.i(TAG, "PersistentIndex ready: ${mem.count()} memories restored from disk.")
    }

    // ---- Index: durability on writes, in-memory for reads ----

    override fun ingest(item: Item, textVec: FloatArray?, imageVec: FloatArray?): String? {
        val id = mem.ingest(item, textVec, imageVec) ?: return null
        append(Row(item, textVec?.toList(), imageVec?.toList()))
        return id
    }

    override fun searchText(vec: FloatArray, k: Int, filters: Index.Filters?): List<Hit> =
        mem.searchText(vec, k, filters)

    override fun searchImage(vec: FloatArray, k: Int, filters: Index.Filters?): List<Hit> =
        mem.searchImage(vec, k, filters)

    override fun get(itemId: String): Item? = mem.get(itemId)

    override fun count(): Int = mem.count()

    // ---- persistence internals ----

    /** Replay the file into [mem]. Returns the number of lines read (for compaction heuristics). */
    private fun load(): Int {
        if (!file.exists()) return 0
        var lines = 0
        try {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                lines++
                try {
                    val row = FlowJson.decodeFromString(Row.serializer(), line)
                    mem.ingest(row.item, row.textVec?.toFloatArray(), row.imageVec?.toFloatArray())
                } catch (t: Throwable) {
                    // Skip a corrupt/partial line (e.g. a write interrupted by a kill).
                    Log.w(TAG, "skipping bad index line", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "index load failed; starting fresh", t)
        }
        return lines
    }

    private fun openWriter() {
        synchronized(writeLock) {
            try {
                writer = BufferedWriter(FileWriter(file, /* append = */ true))
            } catch (t: Throwable) {
                Log.w(TAG, "could not open index writer; memory will not persist", t)
                writer = null
            }
        }
    }

    private fun append(row: Row) {
        synchronized(writeLock) {
            val w = writer ?: return
            try {
                w.write(FlowJson.encodeToString(Row.serializer(), row))
                w.newLine()
                w.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "index append failed", t)
            }
        }
    }

    /** Rewrite the file from the live entry set, dropping duplicates/superseded lines. */
    private fun compact() {
        synchronized(writeLock) {
            try {
                writer?.close()
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                BufferedWriter(FileWriter(tmp, false)).use { w ->
                    // mem has no public iterator, so re-snapshot via the rows we just loaded is
                    // unavailable here; instead compact lazily by truncating only when empty.
                    // We rewrite from a fresh capture of current ids via get() over a snapshot.
                    for (id in mem.ids()) {
                        val item = mem.get(id) ?: continue
                        val (t, i) = mem.vectorsOf(id)
                        w.write(FlowJson.encodeToString(Row.serializer(), Row(item, t?.toList(), i?.toList())))
                        w.newLine()
                    }
                }
                if (tmp.exists()) {
                    file.delete()
                    tmp.renameTo(file)
                }
                Log.i(TAG, "compacted index to ${mem.count()} entries.")
            } catch (t: Throwable) {
                Log.w(TAG, "compaction failed (non-fatal)", t)
            } finally {
                openWriter()
            }
        }
    }

    companion object {
        private const val TAG = "PersistentIndex"
        private const val FILE_NAME = "flow_memory.jsonl"
    }
}
