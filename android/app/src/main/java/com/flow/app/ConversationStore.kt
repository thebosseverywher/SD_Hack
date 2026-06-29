package com.flow.app

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Disk-backed, thread-safe store for Travis's CONVERSATIONAL memory — the live multi-turn
 * dialogue thread, kept strictly distinct from the ambient RAG memory in [AmbientMemory] /
 * [Index]:
 *   - short-term  = the recent verbatim dialogue turns (this store), and
 *   - long-term   = the ambient memory (RAG corpus).
 * Both are composed into each prompt by [Travis] (see [Prompts.buildTravisPrompt]).
 *
 * Persistence mirrors [PersistentIndex]: an append-only JSON Lines file in filesDir, replayed
 * on construction so the chat thread survives app restarts / process death. A tiny side file
 * holds a rolling SUMMARY of older turns plus how many leading turns it covers, so a long
 * thread never overflows the 4096-token context window — [Travis] folds old turns into the
 * summary and keeps only the recent verbatim tail in the prompt.
 *
 * Everything stays on-device and private: this is just durable local storage in the app's own
 * sandbox (filesDir), wiped on uninstall or via [clear] ("start a new conversation").
 */
class ConversationStore(context: Context) {

    /** One durable dialogue turn. role is "user" | "travis" (matches [Travis.Turn]). */
    @Serializable
    private data class Record(val role: String, val content: String, val ts: Long)

    /** Persisted rolling-summary state (older turns distilled into [summary]). */
    @Serializable
    private data class Snapshot(val summary: String = "", val summarizedUpTo: Int = 0)

    private val lock = Any()
    private val turns = ArrayList<Record>()
    private val file = File(context.filesDir, FILE_NAME)
    private val summaryFile = File(context.filesDir, SUMMARY_FILE)
    private var writer: BufferedWriter? = null

    // Rolling summary of the OLDEST [summarizedUpTo] turns; the rest stay verbatim.
    private var summaryText: String = ""
    private var summarizedUpTo: Int = 0

    init {
        load()
        loadSummary()
        openWriter()
        Log.i(TAG, "ConversationStore ready: ${turns.size} turns restored (summarizedUpTo=$summarizedUpTo).")
    }

    // ---- reads (snapshots; safe to use off the lock) ----

    /** Every persisted turn (oldest first), as [Travis.Turn] for the UI transcript + prompt. */
    fun turns(): List<Travis.Turn> = synchronized(lock) {
        turns.map { Travis.Turn(it.role, it.content) }
    }

    /** Total number of stored turns. */
    fun size(): Int = synchronized(lock) { turns.size }

    /** The rolling summary of the older, already-folded turns (may be blank). */
    fun summary(): String = synchronized(lock) { summaryText }

    /** How many leading turns are already folded into [summary]. */
    fun summarizedCount(): Int = synchronized(lock) { summarizedUpTo }

    /**
     * The recent verbatim tail — turns NOT yet folded into the summary (oldest first).
     * This is what rides in the prompt alongside [summary].
     */
    fun unsummarizedTurns(): List<Travis.Turn> = synchronized(lock) {
        val from = summarizedUpTo.coerceIn(0, turns.size)
        turns.subList(from, turns.size).map { Travis.Turn(it.role, it.content) }
    }

    // ---- writes (durable) ----

    /** Append one turn and durably persist it. Blank content is ignored. */
    fun append(role: String, content: String) {
        val c = content.trim()
        if (c.isEmpty()) return
        val rec = Record(role, c, System.currentTimeMillis() / 1000)
        synchronized(lock) {
            turns.add(rec)
            appendLine(rec)
        }
    }

    /**
     * Replace the rolling summary and advance the folded-turn cursor by [additionalFolded]
     * (the count of leading verbatim turns this new summary now subsumes). Persists.
     */
    fun foldSummary(newSummary: String, additionalFolded: Int) {
        synchronized(lock) {
            summaryText = newSummary.trim()
            summarizedUpTo = (summarizedUpTo + additionalFolded).coerceIn(0, turns.size)
            persistSummary()
        }
    }

    /** Start a brand-new conversation: wipe turns + summary on disk and in memory. */
    fun clear() {
        synchronized(lock) {
            turns.clear()
            summaryText = ""
            summarizedUpTo = 0
            try { writer?.close() } catch (t: Throwable) { Log.w(TAG, "writer close failed", t) }
            writer = null
            file.delete()
            summaryFile.delete()
            openWriter()
        }
    }

    // ---- persistence internals (mirror PersistentIndex) ----

    /** Replay the turns file into memory. */
    private fun load() {
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    turns.add(FlowJson.decodeFromString(Record.serializer(), line))
                } catch (t: Throwable) {
                    // Skip a corrupt/partial line (e.g. a write interrupted by a kill).
                    Log.w(TAG, "skipping bad conversation line", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "conversation load failed; starting fresh", t)
        }
    }

    /** Restore the rolling-summary snapshot, clamped to the loaded turn count. */
    private fun loadSummary() {
        if (!summaryFile.exists()) return
        try {
            val snap = FlowJson.decodeFromString(Snapshot.serializer(), summaryFile.readText())
            summaryText = snap.summary
            summarizedUpTo = snap.summarizedUpTo.coerceIn(0, turns.size)
        } catch (t: Throwable) {
            Log.w(TAG, "summary load failed; ignoring", t)
        }
    }

    private fun openWriter() {
        try {
            writer = BufferedWriter(FileWriter(file, /* append = */ true))
        } catch (t: Throwable) {
            Log.w(TAG, "could not open conversation writer; turns will not persist", t)
            writer = null
        }
    }

    private fun appendLine(rec: Record) {
        val w = writer ?: return
        try {
            w.write(FlowJson.encodeToString(Record.serializer(), rec))
            w.newLine()
            w.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "conversation append failed", t)
        }
    }

    /** Overwrite the small summary snapshot file. */
    private fun persistSummary() {
        try {
            summaryFile.writeText(
                FlowJson.encodeToString(Snapshot.serializer(), Snapshot(summaryText, summarizedUpTo))
            )
        } catch (t: Throwable) {
            Log.w(TAG, "summary persist failed", t)
        }
    }

    companion object {
        private const val TAG = "ConversationStore"
        private const val FILE_NAME = "flow_conversation.jsonl"
        private const val SUMMARY_FILE = "flow_conversation_summary.json"
    }
}
