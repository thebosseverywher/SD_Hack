package com.flow.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * AmbientMemory — the MiniMe-style ambient memory layer.
 *
 * Raw activity/photo Items (Trail + Trove) are already ingested into the [index] by the
 * existing capture sinks; AmbientMemory simply OBSERVES that stream (via [onActivity]) to:
 *   - keep a small newest-first buffer of recent events for recency-aware recall, and
 *   - periodically CONSOLIDATE batches of raw events into a single first-person "memory
 *     note" via the on-device LLM, ingested as a high-level Item(source="memory") so Travis
 *     can retrieve distilled memories instead of only raw rows.
 *
 * Everything stays on-device. The consolidation loop is cheap and resilient: any LLM failure
 * just skips that batch, and it does nothing while the LLM is still warming up.
 */
class AmbientMemory(
    private val inference: Inference,
    private val index: Index,
    private val deviceId: String
) {
    // Plain monitor: onActivity is called synchronously from the (non-coroutine) capture sink,
    // so a suspend Mutex isn't usable here. This guards both buffers.
    private val monitor = Any()

    // Newest-first ring of recent raw events for recency-aware recall (capped).
    private val recent = ArrayDeque<Item>()

    // Newest-first ring of consolidated first-person memory notes (source="memory"), for the UI.
    private val notes = ArrayDeque<Item>()

    /** The most recent consolidated memory notes (newest first), capped at [n]. */
    fun recentNotes(n: Int = 20): List<Item> = synchronized(monitor) { notes.take(n) }

    // Raw events observed since the last consolidation pass (drained when consolidated).
    private val pending = ArrayList<Item>()

    /** Called by the capture sink for every raw activity/photo Item. Cheap + non-suspending. */
    fun onActivity(item: Item) {
        if (item.text.isBlank()) return
        synchronized(monitor) {
            recent.addFirst(item)
            while (recent.size > RECENT_CAP) recent.removeLast()
            pending.add(item)
        }
    }

    /** The most recent buffered items (newest first), capped at [n]. */
    fun recentContext(n: Int = 12): List<Item> = synchronized(monitor) {
        recent.take(n)
    }

    /**
     * Launch the always-on consolidation loop. Every [CONSOLIDATE_INTERVAL] ms — or sooner once
     * at least [BATCH] new events have accumulated — distill the new events into one memory note.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var lastConsolidate = System.currentTimeMillis()
            while (isActive) {
                delay(TICK_MS)
                // Skip entirely while the LLM is still warming up; don't reset the timer.
                if (!inference.llmReady) continue
                val now = System.currentTimeMillis()
                val due = now - lastConsolidate >= CONSOLIDATE_INTERVAL
                val full = pendingCount() >= BATCH
                if (!due && !full) continue
                lastConsolidate = now
                consolidate()
            }
        }
    }

    private fun pendingCount(): Int = synchronized(monitor) { pending.size }

    private fun drainPending(): List<Item> = synchronized(monitor) {
        if (pending.isEmpty()) emptyList()
        else ArrayList(pending).also { pending.clear() }
    }

    /** Summarize the new events into a single first-person memory note and ingest it. */
    private fun consolidate() {
        val batch = drainPending()
        if (batch.size < MIN_BATCH) return            // skip thin batches
        try {
            val summary = summarize(batch)
            if (summary.isBlank()) return
            // Skip near-identical consecutive notes (avoid duplicate "you were on Instagram" cards).
            val prev = synchronized(monitor) { notes.firstOrNull()?.text }
            if (prev != null && prev.trim().equals(summary.trim(), ignoreCase = true)) return
            val note = Item(
                id = UUID.randomUUID().toString(),
                device_id = deviceId,
                source = "memory",
                ts = System.currentTimeMillis() / 1000,
                app_context = null,
                text = summary,
                type = "note",
                fields = JsonObject(emptyMap())
            )
            index.ingest(note, textVec = inference.embedText(summary))
            synchronized(monitor) {
                notes.addFirst(note)
                while (notes.size > NOTE_CAP) notes.removeLast()
            }
        } catch (t: Throwable) {
            // Resilient: a failed batch is simply dropped, never crashing the background loop.
            Log.w(TAG, "consolidation skipped (failed batch)", t)
        }
    }

    private fun summarize(batch: List<Item>): String {
        val deduped = batch.distinctBy { (it.app_context ?: "") + "|" + it.text.trim().take(80) }
        val events = deduped.takeLast(SUMMARY_MAX_EVENTS).joinToString("\n") { item ->
            val ctx = friendlyApp(item.app_context)?.let { " [$it]" } ?: ""
            "-$ctx ${item.text}"
        }
        val prompt = buildString {
            appendLine(
                "You are the user's private on-device memory. From the recent phone activity " +
                    "below, write ONE short first-person memory note (1-2 sentences) summarizing " +
                    "what the user was doing. Be concrete (apps, names, numbers). Do not invent " +
                    "anything not present."
            )
            appendLine()
            appendLine("Recent activity:")
            appendLine(events)
            appendLine()
            append("Memory note: ")
        }
        val sb = StringBuilder()
        inference.generate(prompt, maxTokens = 80, stop = listOf("\n\n")) { tok ->
            sb.append(tok)
        }
        return sb.toString().trim()
    }

    companion object {
        private const val TAG = "Flow/AmbientMemory"

        /** Newest-first recency buffer cap (a few hundred raw events). */
        private const val RECENT_CAP = 300

        /** How long between time-based consolidation passes. */
        private const val CONSOLIDATE_INTERVAL = 90_000L

        /** Consolidate early once this many new raw events have accumulated. */
        private const val BATCH = 15

        /** How often the loop wakes to check the interval / batch conditions. */
        private const val TICK_MS = 5_000L

        /** Cap how many raw events feed a single summarization prompt. */
        private const val SUMMARY_MAX_EVENTS = 40

        /** Cap of stored consolidated notes for the Memory tab. */
        private const val NOTE_CAP = 100

        /** Minimum new raw events before a consolidation note is worth writing. */
        private const val MIN_BATCH = 3
    }
}
