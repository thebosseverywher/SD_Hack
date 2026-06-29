package com.flow.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Travis — Flow's on-device memory companion (replaces the old federated [Ask]).
 *
 * Travis is modeled on Minimi/MiniMe: everything the user does on the phone is passively
 * captured (Trail + Trove), continuously distilled into a private [AmbientMemory], and
 * Travis answers grounded ONLY in that local memory. There is NO laptop, NO pairing, NO
 * federation — all retrieval and generation happen on-device.
 *
 * Travis is STATEFUL: when constructed with a [ConversationStore] he remembers the running
 * dialogue thread (short-term conversational memory) — distinct from the ambient RAG memory
 * (long-term). Both are composed into every prompt, and the thread is disk-backed so it
 * survives restarts. With no store he degrades to the previous stateless behaviour, using
 * whatever [history] the caller passes.
 *
 * Flow per [ask]:
 *   1. embed the query (384-d, text/semantic space)
 *   2. search the local ambient-memory index for the most relevant items
 *   3. pull the most recent buffered events for recency awareness
 *   4. build a grounded RAG prompt = TRAVIS persona + retrieved context + the conversation
 *      summary + the recent verbatim dialogue turns, all fit within the 4096-token budget
 *   5. stream the local LLM, surfacing tokens via [onProgress]
 *   6. persist the user + assistant turns and return { answer, sources[] }
 */
class Travis(
    private val inference: Inference,
    private val index: Index,
    private val memory: AmbientMemory,
    /**
     * Disk-backed conversational memory. When non-null Travis is fully stateful: he reads the
     * prior thread from here (ignoring any caller-supplied [ask] history), persists each turn,
     * and summarizes old turns. When null, behaviour is the legacy stateless one.
     */
    private val store: ConversationStore? = null
) {
    /** One turn of the running conversation. role is "user" | "travis". */
    data class Turn(val role: String, val content: String)

    data class Progress(
        val hits: List<Hit> = emptyList(),
        val answer: String = "",
        val done: Boolean = false
    )

    /** The persisted conversation thread (oldest first) — for the UI to restore on launch. */
    fun history(): List<Turn> = store?.turns() ?: emptyList()

    /** Start a fresh conversation thread: clears the persisted turns + rolling summary. */
    fun newConversation() = store?.clear() ?: Unit

    /**
     * Suspending ask. Emits incremental [Progress] via [onProgress] so the UI can show what
     * Travis is recalling (hits) first, then stream the answer. Returns the final [Answer].
     *
     * When a [ConversationStore] is wired, Travis sources the prior conversation from it and
     * persists this exchange; the [history] argument is then ignored (kept for the stateless
     * fallback + binary compatibility with existing callers).
     */
    suspend fun ask(
        query: String,
        history: List<Turn> = emptyList(),
        onProgress: (Progress) -> Unit = {}
    ): Answer {
        // Persist the user's turn up-front so it survives even if generation is interrupted.
        store?.append("user", query)

        // 1 + 2: local semantic retrieval over the ambient (long-term) memory.
        val qVec = inference.embedText(query)
        val hits = index.searchText(qVec, Config.TOP_K)

        // 3: recency — the newest things Travis has observed, even if not top-ranked.
        val recent = memory.recentContext()

        // Conversational (short-term) memory: the rolling summary of older turns + the recent
        // verbatim tail. The store already holds the just-added user turn, so drop it from the
        // "prior" turns (the live query rides separately in the current user turn).
        val convoSummary = store?.summary() ?: ""
        val priorTurns = if (store != null) store.unsummarizedTurns().dropLastUserEcho() else history

        // Surface what Travis is recalling before generation begins.
        onProgress(Progress(hits = hits))

        // Truly-empty case: nothing observed AND nothing said yet — answer honestly, no LLM burn.
        if (hits.isEmpty() && recent.isEmpty() && priorTurns.isEmpty() && convoSummary.isBlank()) {
            val empty = Answer(
                "I haven't observed anything on your phone yet, so I don't have this in memory. " +
                    "Give me a little while to start remembering your activity.",
                emptyList()
            )
            store?.append("travis", empty.answer)
            onProgress(Progress(hits = hits, answer = empty.answer, done = true))
            return empty
        }

        // 4: grounded persona prompt (RAG context + recent activity + summary + dialogue).
        val prompt = Prompts.buildTravisPrompt(query, hits, recent, priorTurns, convoSummary, index)

        // 5: stream the local LLM, appending tokens as they arrive. Stop on the chat
        // template turn markers so Travis never bleeds into a fake next turn.
        val sb = StringBuilder()
        inference.generate(
            prompt,
            maxTokens = 384,
            stop = listOf("<end_of_turn>", "<start_of_turn>", "<eos>")
        ) { tok ->
            sb.append(tok)
            onProgress(Progress(hits = hits, answer = cleanAnswer(sb.toString())))
        }
        val finalText = cleanAnswer(sb.toString())

        // 6: persist Travis's reply + assemble citations from the hits he grounded on.
        store?.append("travis", finalText)
        val answer = Answer(
            answer = finalText,
            sources = hits.map { h ->
                AnswerSource(
                    item_id = h.item_id,
                    device_id = h.device_id,
                    file_ref = index.get(h.item_id)?.file_ref
                )
            }
        )
        onProgress(Progress(hits = hits, answer = answer.answer, done = true))

        // Keep the thread within budget: fold the oldest turns into the rolling summary once
        // the verbatim tail grows long. Cheap + occasional; failures are non-fatal.
        store?.let { maybeSummarize(it) }
        return answer
    }

    /** Drop the just-appended user echo from the unsummarized tail (the live query rides separately). */
    private fun List<Turn>.dropLastUserEcho(): List<Turn> =
        if (isNotEmpty() && last().role == "user") dropLast(1) else this

    /**
     * Compact the thread when its verbatim tail exceeds the keep window: distill the oldest
     * [SUMMARIZE_CHUNK] turns into the rolling summary so the prompt never approaches the
     * context limit. Runs on the same (off-main) thread as generation; gated so it's rare.
     */
    private fun maybeSummarize(store: ConversationStore) {
        try {
            val tail = store.unsummarizedTurns()
            if (tail.size <= KEEP_RECENT_TURNS + SUMMARIZE_CHUNK) return
            val toFold = tail.take(SUMMARIZE_CHUNK)
            val updated = summarizeTurns(store.summary(), toFold)
            if (updated.isNotBlank()) store.foldSummary(updated, toFold.size)
        } catch (t: Throwable) {
            android.util.Log.w("Travis", "summary fold skipped", t)
        }
    }

    /** Distill prior-summary + a batch of turns into one short running summary via the LLM. */
    private fun summarizeTurns(prev: String, turns: List<Turn>): String {
        val convo = turns.joinToString("\n") { t ->
            val who = if (t.role == "travis") "Travis" else "User"
            "$who: ${t.content.trim()}"
        }
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append(
                "Maintain a running summary of this conversation between the User and Travis " +
                    "(Travis is the user's private on-device memory assistant). Rewrite it in 2-4 " +
                    "concise third-person sentences capturing the facts, topics, and any unresolved " +
                    "questions so the thread can continue. Use ONLY what is stated; invent nothing."
            )
            if (prev.isNotBlank()) append("\n\nSummary so far:\n").append(prev.trim())
            append("\n\nNew turns:\n").append(convo)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        val sb = StringBuilder()
        inference.generate(
            prompt,
            maxTokens = 160,
            stop = listOf("<end_of_turn>", "<start_of_turn>", "<eos>")
        ) { sb.append(it) }
        return cleanAnswer(sb.toString())
    }

    companion object {
        /** Keep this many most-recent verbatim turns; older ones get folded into the summary. */
        private const val KEEP_RECENT_TURNS = 8

        /** How many leading turns to distill per summarization pass. */
        private const val SUMMARIZE_CHUNK = 6

        /**
         * Scrub anything model- or scaffolding-shaped out of the streamed answer so the UI
         * shows a clean, agent-like reply: chat-template tokens, any leftover [item-id]
         * citations, and dangling role labels. Keeps Travis conversational, not a data dump.
         */
        private fun cleanAnswer(raw: String): String {
            var s = raw
            // Strip chat-template / role markers if any leak into the stream.
            for (m in listOf(
                "<start_of_turn>model", "<start_of_turn>user", "<start_of_turn>",
                "<end_of_turn>", "<bos>", "<eos>",
                "<|im_start|>", "<|im_end|>", "</s>", "<s>"
            )) {
                s = s.replace(m, "")
            }
            // Drop bracketed citation ids like [a1b2c3d4] the model may have echoed.
            s = s.replace(Regex("\\[[0-9a-fA-F-]{6,}]"), "")
            // Remove a leading "Travis:" / "Assistant:" label if the model parrots it.
            s = s.replace(Regex("^\\s*(Travis|Assistant)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            // A 1B model may stack openers ("Hi there! Sure, …") — run the strips twice.
            repeat(2) {
                s = s.replace(Regex(
                    "^\\s*(hello there|hi there|hey there|hello|hi|hey|greetings)\\b[^.!?\\n]*[.!?,:-]?\\s*",
                    RegexOption.IGNORE_CASE), "")
                s = s.replace(Regex(
                    "^\\s*(i'?m|i am|let me|i will|i'?ll|just)\\s+" +
                        "(gathering|getting|checking|looking|searching|pulling|recalling|reviewing|going through)\\b" +
                        "[^.!?\\n]*[.!?]?\\s*",
                    RegexOption.IGNORE_CASE), "")
                s = s.replace(Regex(
                    "^\\s*(sure|of course|certainly|absolutely|no problem|great question|good question)\\b[\\s,!.:-]*",
                    RegexOption.IGNORE_CASE), "")
            }
            s = s.replace(Regex("\\bmemory notes?\\b", RegexOption.IGNORE_CASE), "memory")
            return s.trim()
        }
    }
}

/**
 * Prompt construction for Travis. The persona keeps answers grounded, concise, and honest;
 * citations reference item ids in square brackets so the UI can resolve sources.
 */
object Prompts {

    private const val PERSONA =
        "You are Travis, the user's private on-device memory. You quietly notice what they do " +
            "on their phone and recall it when asked. Answer the question directly in your very " +
            "first sentence — no greeting, no 'let me check', no 'I'm gathering your memory', no " +
            "preamble of any kind. Be concrete: use the specific details, names, and times from the " +
            "memory below. Default to one to three sentences; only go longer when the question truly " +
            "needs it. Speak warmly and naturally in the first person, like a friend who was paying " +
            "attention, but stay brief. Use ONLY the memory below — never invent, guess, or assume. " +
            "If the memory does not contain the answer, say so plainly in a single sentence (for " +
            "example: \"I don't have that in your memory.\"). Never repeat the notes back word for " +
            "word, and never mention app package names, ids, sources, scores, or that you are reading " +
            "a list or 'notes'."

    // ---- context budget (Gemma multi-turn, maxNumTokens=4096) ----
    /** Hard model context window (must match [LlmEngine] EngineConfig.maxNumTokens for GPU/CPU). */
    private const val MAX_CTX_TOKENS = 4096
    /** Headroom reserved for the streamed answer (ask() caps generation at 384) + slack. */
    private const val GEN_RESERVE_TOKENS = 640
    /** Crude tokens≈chars/4 estimate; deliberately conservative so we never overflow. */
    private const val CHARS_PER_TOKEN = 4

    /**
     * Build a Gemma (<start_of_turn>) formatted MULTI-TURN prompt. The persona, RAG memory and
     * the conversation summary ride with the CURRENT user turn (Gemma has no system role); the
     * recent verbatim dialogue turns precede it as alternating user/model turns. Only as many
     * recent turns as fit the 4096-token budget are included (newest kept, oldest dropped —
     * those are already represented in [summary]).
     */
    fun buildTravisPrompt(
        query: String,
        hits: List<Hit>,
        recent: List<Item>,
        history: List<Travis.Turn>,
        summary: String,
        index: Index,
        nowSec: Long = System.currentTimeMillis() / 1000L
    ): String {
        val notes = LinkedHashSet<String>()
        hits.forEach { h ->
            val it = index.get(h.item_id)
            noteLine(it?.app_context, h.text, it?.ts ?: 0L, nowSec)?.let { n -> notes.add(n) }
        }
        recent.forEach { item ->
            noteLine(item.app_context, item.text, item.ts, nowSec)?.let { notes.add(it) }
        }
        val memoryBlock =
            if (notes.isEmpty()) "(your memory is empty so far)"
            else notes.take(14).joinToString("\n") { "- ${it.take(220)}" }

        val today = Instant.ofEpochSecond(nowSec).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEEE, MMM d"))

        // Gemma chat format: persona + memory + summary ride with the CURRENT user turn.
        val preamble = buildString {
            append(PERSONA)
            append("\n\nToday is ").append(today)
            append(". Your memory of the user's recent activity (newest first):\n").append(memoryBlock)
            if (summary.isNotBlank()) {
                append("\n\nSummary of earlier in this conversation:\n").append(summary.trim())
            }
        }
        val currentTurn = "<start_of_turn>user\n$preamble\n\nQuestion: ${query.trim()}" +
            "<end_of_turn>\n<start_of_turn>model\n"

        // Budget the recent verbatim turns: keep newest-first until we run out of room, then
        // emit them in chronological order so the dialogue reads correctly.
        var remainingChars = (MAX_CTX_TOKENS - GEN_RESERVE_TOKENS) * CHARS_PER_TOKEN - currentTurn.length
        val kept = ArrayDeque<Travis.Turn>()
        for (t in history.asReversed()) {
            val role = if (t.role == "travis") "model" else "user"
            val rendered = "<start_of_turn>$role\n${t.content.trim()}<end_of_turn>\n"
            if (rendered.length > remainingChars) break
            remainingChars -= rendered.length
            kept.addFirst(t)
        }

        return buildString {
            kept.forEach { t ->
                val role = if (t.role == "travis") "model" else "user"
                append("<start_of_turn>$role\n").append(t.content.trim()).append("<end_of_turn>\n")
            }
            append(currentTurn)
        }
    }

    /** A light, human relative-time phrase from a unix-seconds ts, or null if unknown/future. */
    private fun relativeWhen(tsSec: Long, nowSec: Long): String? {
        if (tsSec <= 0L) return null
        val zone = ZoneId.systemDefault()
        val then = Instant.ofEpochSecond(tsSec).atZone(zone)
        val now = Instant.ofEpochSecond(nowSec).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        return when {
            days < 0L -> null
            days == 0L ->
                if (ChronoUnit.MINUTES.between(then, now) < 60L) "just now" else "earlier today"
            days == 1L -> "yesterday"
            days in 2L..6L -> "earlier this week"
            days in 7L..30L -> "a few weeks ago"
            else -> "a while ago"
        }
    }

    /** One clean natural-language memory note with a time hint, or null if nothing worth showing. */
    private fun noteLine(appContext: String?, text: String, tsSec: Long, nowSec: Long): String? {
        val t = text.trim()
        if (t.isBlank()) return null
        val where = appContext?.let { friendlyApp(it) }   // -> shared top-level friendlyApp
        val whenStr = relativeWhen(tsSec, nowSec)
        val head = StringBuilder()
        if (whenStr != null) head.append(whenStr.replaceFirstChar { it.uppercase() })
        if (where != null && !t.startsWith(where, ignoreCase = true)) {
            head.append(if (head.isEmpty()) "In $where" else ", in $where")
        }
        return if (head.isEmpty()) t else "$head — $t"
    }
}
