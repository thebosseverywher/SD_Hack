package com.flow.app

import kotlinx.coroutines.delay

/**
 * Ask — federated RAG orchestration (spec §5.1), Android side.
 *
 * Steps (shared/protocol.md §Query → answer flow):
 *   1. embed the query (text -> 384; CLIP-text -> 512 is a TODO for photo queries)
 *   2. search the LOCAL index
 *   3. broadcast QUERY to peers and collect RESULTS within Config.QUERY_TIMEOUT_MS
 *   4. fuse local + peer hits via RRF ([Fusion])
 *   5. build a cited RAG prompt and stream the local LLM ([Inference.generate])
 *   6. return { answer, sources[] }
 */
class Ask(
    private val inference: Inference,
    private val index: Index,
    private val federation: FederationManager
) {
    data class Progress(
        val hits: List<Hit> = emptyList(),
        val answer: String = "",
        val done: Boolean = false
    )

    /**
     * Suspending ask. Emits incremental [Progress] via [onProgress] so the UI can show
     * hits first, then stream the answer. Returns the final [Answer].
     */
    suspend fun ask(query: String, onProgress: (Progress) -> Unit = {}): Answer {
        // 1 + 2: local retrieval.
        val qVec = inference.embedText(query)
        val localHits = index.searchText(qVec, Config.TOP_K)

        // 3: fan out to peers.
        val queryId = federation.broadcastQuery(query, Config.TOP_K)

        // Wait up to the timeout, surfacing partial results as they arrive, but return as
        // soon as every peer has replied (spec §4.4: partial allowed) instead of always
        // blocking for the full QUERY_TIMEOUT_MS.
        val deadline = System.currentTimeMillis() + Config.QUERY_TIMEOUT_MS
        var peerHits: List<Hit> = federation.collectedHits(queryId)
        while (System.currentTimeMillis() < deadline && !federation.allPeersReplied(queryId)) {
            delay(POLL_MS)
            peerHits = federation.collectedHits(queryId)
            val partial = Fusion.fuse(listOf(localHits, peerHits))
            onProgress(Progress(hits = partial))
        }
        peerHits = federation.collectedHits(queryId)
        federation.finishQuery(queryId)

        // 4: final fusion (text + image spaces would each contribute a list here).
        val fused = Fusion.fuse(listOf(localHits, peerHits))
        onProgress(Progress(hits = fused))

        if (fused.isEmpty()) {
            val empty = Answer("I couldn't find anything relevant on your devices.", emptyList())
            onProgress(Progress(answer = empty.answer, done = true))
            return empty
        }

        // 5: cited RAG prompt + stream the local LLM.
        val prompt = Prompts.buildRagPrompt(query, fused)
        val sb = StringBuilder()
        inference.generate(prompt, maxTokens = 256, stop = listOf("\n\n")) { tok ->
            sb.append(tok)
            onProgress(Progress(hits = fused, answer = sb.toString()))
        }

        // 6: assemble citations from the hits we grounded on.
        val answer = Answer(
            answer = sb.toString().trim(),
            sources = fused.map { h ->
                AnswerSource(item_id = h.item_id, device_id = h.device_id, file_ref = index.get(h.item_id)?.file_ref)
            }
        )
        onProgress(Progress(hits = fused, answer = answer.answer, done = true))
        return answer
    }

    companion object {
        private const val POLL_MS = 100L
    }
}

/**
 * RAG prompt + citation contract (spec §5.5). Mirror the shared/prompts text files
 * when they exist so the phone and laptop ground identically.
 */
object Prompts {
    fun buildRagPrompt(query: String, hits: List<Hit>): String {
        val context = hits.joinToString("\n") { h ->
            "[${h.item_id}] (${h.source}/${h.type} @${h.device_id}) ${h.text}"
        }
        return """
            You are Flow, an on-device assistant. Answer ONLY from the context below.
            Cite the item ids you used in square brackets. If the context is insufficient,
            say so plainly.

            Context:
            $context

            Question: $query
            Answer:
        """.trimIndent()
    }
}
