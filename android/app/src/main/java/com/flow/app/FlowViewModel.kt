package com.flow.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Minimum number of indexed on-device memories before Travis can be asked. */
const val MIN_INDEXED_TO_ASK = 5

/** UI-facing state for the whole app (mobile-only, always-on Travis). */
data class UiState(
    val query: String = "",
    // The running conversation with Travis (oldest first). The latest "travis" turn is
    // streamed in live as tokens arrive; there is no separate top-level answer field.
    val messages: List<Travis.Turn> = emptyList(),
    val asking: Boolean = false,
    // Grounding hits Travis recalled for the most recent answer (shown under the transcript).
    val lastHits: List<Hit> = emptyList(),
    // Per-sensor toggles (spec §6.1). Trove/Trail map to real capture.
    val troveOn: Boolean = true,
    val trailOn: Boolean = true,
    val statusLine: String = "Starting…",
    // Readiness gate: Travis is enabled only once the on-device model has finished loading
    // AND enough phone activity has been observed/indexed into ambient memory.
    val modelReady: Boolean = false,    // LLM init attempt finished
    val modelLoaded: Boolean = false,   // a real on-device model loaded (vs. stub fallback)
    val indexedCount: Int = 0,
    val dataTarget: Int = MIN_INDEXED_TO_ASK,
    val dataReady: Boolean = false,
    val canAsk: Boolean = false,
    // Small telemetry line for the Travis screen.
    val embedEpLabel: String = "",
    val memoryCount: Int = 0
)

class FlowViewModel(app: Application) : AndroidViewModel(app) {
    private val flowApp get() = getApplication<FlowApp>()

    private val _state = MutableStateFlow(UiState(trailOn = Trail.enabled))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Restore Travis's persisted conversation thread so the chat survives app restarts.
        // history() is disk-backed when a ConversationStore is wired, and safely returns an
        // empty list otherwise — so this is a no-op in the legacy stateless configuration.
        _state.value = _state.value.copy(messages = flowApp.travis.history())
        // Begin loading the LLM off the main thread, then observe readiness + memory growth so
        // the UI can unlock Travis once both the model is loaded and enough activity is seen.
        flowApp.inference.warmUp()
        viewModelScope.launch { observeReadiness() }
    }

    /** Polls model-load state and index growth; keeps the gate + counters fresh. */
    private suspend fun observeReadiness() {
        while (true) {
            val modelReady = flowApp.inference.llmReady
            val count = flowApp.index.count()
            val dataReady = count >= MIN_INDEXED_TO_ASK
            val canAsk = modelReady && dataReady
            _state.value = _state.value.copy(
                modelReady = modelReady,
                modelLoaded = flowApp.inference.llmModelLoaded,
                indexedCount = count,
                memoryCount = count,
                dataReady = dataReady,
                canAsk = canAsk,
                embedEpLabel = flowApp.inference.embedEpLabel,
                statusLine = if (canAsk) "Travis ready" else "Building memory…"
            )
            // Poll quickly while still preparing; slow down once fully ready.
            delay(if (modelReady && dataReady) 2000L else 600L)
        }
    }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }

    /**
     * Send the current query to Travis as a new conversation turn and stream the reply.
     * Appends a "user" turn immediately, then a "travis" turn whose content grows live as
     * tokens arrive (and whose grounding hits are exposed via [UiState.lastHits]).
     */
    fun sendMessage() {
        val s = _state.value
        if (!s.canAsk || s.asking) return
        val text = s.query.trim()
        if (text.isEmpty()) return

        // History is the conversation BEFORE this turn (Travis appends the new query itself).
        val history = s.messages
        val withUser = s.messages + Travis.Turn(role = "user", content = text)
        _state.value = s.copy(query = "", asking = true, messages = withUser, lastHits = emptyList())

        // Run retrieval + on-device generation OFF the main thread: LlmEngine.generate()
        // blocks on a latch, so doing this on the main dispatcher freezes the UI (ANR).
        // StateFlow.value is thread-safe; Compose still collects it on the main thread.
        viewModelScope.launch(Dispatchers.Default) {
            flowApp.travis.ask(text, history = history) { p ->
                // Replace the trailing "travis" turn with the latest streamed answer.
                _state.value = _state.value.copy(
                    messages = withUser + Travis.Turn(role = "travis", content = p.answer),
                    lastHits = p.hits
                )
            }
            _state.value = _state.value.copy(asking = false)
        }
    }

    /**
     * Start a fresh chat: clears Travis's persisted thread (rolling summary + turns) and wipes
     * the on-screen transcript + grounding cue. When no ConversationStore is wired this still
     * clears the in-memory transcript; the backend clear is a safe no-op.
     */
    fun newConversation() {
        flowApp.travis.newConversation()
        _state.value = _state.value.copy(messages = emptyList(), lastHits = emptyList())
    }

    /** Consolidated "what Travis remembers" notes, newest first, for the Memory screen. */
    fun recentNotes(): List<Item> = flowApp.ambientMemory.recentNotes(20)

    /** Raw recent activity rows, newest first, shown under the notes on the Memory screen. */
    fun recentActivity(): List<Item> = flowApp.ambientMemory.recentContext(30)

    fun setTrove(on: Boolean) {
        _state.value = _state.value.copy(troveOn = on)
        if (on) flowApp.troveIndexer?.startObserving() else flowApp.troveIndexer?.stopObserving()
    }

    fun setTrail(on: Boolean) {
        Trail.enabled = on
        _state.value = _state.value.copy(trailOn = on)
    }

    /** Telemetry feed for a future panel (spec §6.3): last per-stage timings. */
    fun lastTimings(): Map<String, InferenceTiming> = flowApp.inference.lastTimings
}
