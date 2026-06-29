package com.flow.app

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Local vector store interface (spec §2.1–2.3).
 *
 * Two embedding spaces are kept strictly separate (never mixed):
 *   - text/semantic : Config.TEXT_DIM (384, all-MiniLM)
 *   - image         : Config.IMAGE_DIM (512, CLIP)
 * A photo produces BOTH an image embedding and a text embedding of its OCR.
 *
 * INTEGRATION POINT: the production implementation should be backed by ObjectBox
 * (HNSW vector index) or SQLite + sqlite-vec (`vec0`), using the exact schema from
 * spec §2.1 so it matches the desktop engine. [InMemoryIndex] below is a coherent
 * fallback so the skeleton runs end-to-end with zero native dependencies.
 */
interface Index {

    /** Filters for a search (spec §2.3): any null field is "don't filter". */
    data class Filters(
        val sources: Set<String>? = null,
        val types: Set<String>? = null,
        val sinceTs: Long? = null,
        val untilTs: Long? = null
    )

    /**
     * Upsert an item plus (optionally) its two vectors. Dedupe is by [Item.id]
     * (the caller is expected to set a content-hash-derived id; spec §2.2).
     *
     * Password/OTP-derived text must already be excluded at capture time — this
     * layer additionally drops items whose text is blank.
     *
     * @return the stored item id, or null if the item was rejected.
     */
    fun ingest(item: Item, textVec: FloatArray? = null, imageVec: FloatArray? = null): String?

    fun ingestBatch(items: List<Triple<Item, FloatArray?, FloatArray?>>): List<String> =
        items.mapNotNull { (item, t, i) -> ingest(item, t, i) }

    /** Top-k nearest neighbours in the 384-dim text space. */
    fun searchText(vec: FloatArray, k: Int = Config.TOP_K, filters: Filters? = null): List<Hit>

    /** Top-k nearest neighbours in the 512-dim image space. */
    fun searchImage(vec: FloatArray, k: Int = Config.TOP_K, filters: Filters? = null): List<Hit>

    /** Resolve a stored item by id (used by FETCH handling and result hydration). */
    fun get(itemId: String): Item?

    fun count(): Int
}

/**
 * In-memory cosine index. Not persistent, not ANN-optimized — it exists so the
 * skeleton is internally coherent and unit-testable without a native vector store.
 * Swap in ObjectBox/sqlite-vec behind the same [Index] interface for production.
 */
class InMemoryIndex : Index {

    private data class Entry(
        val item: Item,
        val textVec: FloatArray?,
        val imageVec: FloatArray?
    )

    private val store = ConcurrentHashMap<String, Entry>()

    override fun ingest(item: Item, textVec: FloatArray?, imageVec: FloatArray?): String? {
        if (item.text.isBlank() && textVec == null && imageVec == null) return null
        require(textVec == null || textVec.size == Config.TEXT_DIM) {
            "text vector must be ${Config.TEXT_DIM}-dim"
        }
        require(imageVec == null || imageVec.size == Config.IMAGE_DIM) {
            "image vector must be ${Config.IMAGE_DIM}-dim"
        }
        store[item.id] = Entry(item, textVec, imageVec)   // upsert == dedupe no-op
        return item.id
    }

    override fun searchText(vec: FloatArray, k: Int, filters: Index.Filters?): List<Hit> =
        knn(vec, k, filters) { it.textVec }

    override fun searchImage(vec: FloatArray, k: Int, filters: Index.Filters?): List<Hit> =
        knn(vec, k, filters) { it.imageVec }

    override fun get(itemId: String): Item? = store[itemId]?.item

    override fun count(): Int = store.size

    /** Snapshot of stored ids — used by [PersistentIndex] to compact its on-disk log. */
    fun ids(): List<String> = store.keys.toList()

    /** The (text, image) vectors stored for [id], for durable rewrite. Either may be null. */
    fun vectorsOf(id: String): Pair<FloatArray?, FloatArray?> {
        val e = store[id] ?: return null to null
        return e.textVec to e.imageVec
    }

    private inline fun knn(
        query: FloatArray,
        k: Int,
        filters: Index.Filters?,
        crossinline pick: (Entry) -> FloatArray?
    ): List<Hit> {
        val qn = l2norm(query)
        return store.values.asSequence()
            .filter { matches(it.item, filters) }
            .mapNotNull { e ->
                val v = pick(e) ?: return@mapNotNull null
                val score = cosine(query, qn, v)
                e.item to score
            }
            .sortedByDescending { it.second }
            .take(k)
            .map { (item, score) -> item.toHit(score) }
            .toList()
    }

    private fun matches(item: Item, f: Index.Filters?): Boolean {
        if (f == null) return true
        if (f.sources != null && item.source !in f.sources) return false
        if (f.types != null && item.type !in f.types) return false
        if (f.sinceTs != null && item.ts < f.sinceTs) return false
        if (f.untilTs != null && item.ts > f.untilTs) return false
        return true
    }

    companion object {
        fun l2norm(v: FloatArray): Float {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            return sqrt(s).toFloat()
        }

        /** Cosine similarity; `aNorm` is the precomputed L2 norm of `a`. */
        fun cosine(a: FloatArray, aNorm: Float, b: FloatArray): Double {
            if (a.size != b.size) return -1.0
            var dot = 0.0
            var bn = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                bn += b[i].toDouble() * b[i]
            }
            val denom = aNorm * sqrt(bn).toFloat()
            return if (denom == 0f) 0.0 else dot / denom
        }
    }
}

/** Build a Hit snippet from a stored Item (snippets + thumbs only — never raw libraries). */
fun Item.toHit(score: Double, snippetLen: Int = 240): Hit = Hit(
    item_id = id,
    device_id = device_id,
    score = score,
    source = source,
    type = type,
    text = if (text.length > snippetLen) text.take(snippetLen) + "…" else text,
    app_context = app_context,
    ts = ts,
    fields = fields,
    thumb_b64 = thumb_b64
)

/**
 * Reciprocal Rank Fusion across the text and image spaces and across devices
 * (spec §4.5). Each input list is one ranked result set; we fuse, dedupe by
 * item_id, and return a single ranked list. Source device is preserved on each Hit.
 */
object Fusion {
    private const val RRF_K = 60.0

    fun fuse(rankedLists: List<List<Hit>>, topK: Int = Config.TOP_K): List<Hit> {
        val scoreById = HashMap<String, Double>()
        val bestHit = HashMap<String, Hit>()
        for (list in rankedLists) {
            list.forEachIndexed { rank, hit ->
                val contrib = 1.0 / (RRF_K + rank + 1)
                scoreById.merge(hit.item_id, contrib, Double::plus)
                // Keep the hit with the most informative snippet/thumb.
                val prev = bestHit[hit.item_id]
                if (prev == null || (hit.thumb_b64 != null && prev.thumb_b64 == null)) {
                    bestHit[hit.item_id] = hit
                }
            }
        }
        return scoreById.entries
            .sortedByDescending { it.value }
            .take(topK)
            .mapNotNull { (id, fused) -> bestHit[id]?.copy(score = fused) }
    }
}
