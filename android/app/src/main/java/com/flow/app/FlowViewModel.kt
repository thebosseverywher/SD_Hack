package com.flow.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-facing state for the whole app. */
data class UiState(
    val paired: Boolean = false,
    val peerName: String? = null,
    val query: String = "",
    val results: List<Hit> = emptyList(),
    val answer: String = "",
    val asking: Boolean = false,
    // Per-sensor toggles (spec §6.1). Trove/Trail map to real capture; others are P1.
    val troveOn: Boolean = true,
    val trailOn: Boolean = true,
    val statusLine: String = "Not paired"
)

class FlowViewModel(app: Application) : AndroidViewModel(app) {
    private val flowApp get() = getApplication<FlowApp>()
    private val ask by lazy { Ask(flowApp.inference, flowApp.index, flowApp.federation) }

    private val _state = MutableStateFlow(UiState(trailOn = Trail.enabled))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        flowApp.federation.onSurface = { surface ->
            _state.value = _state.value.copy(statusLine = "Recall: ${surface.event}")
        }
    }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun onPairingScanned(qrText: String) {
        val parsed = Pairing.parse(qrText)
        parsed.onSuccess { info ->
            flowApp.federation.connect(info)
            _state.value = _state.value.copy(
                paired = true,
                peerName = "${info.ip}:${info.port}",
                statusLine = "Paired with ${info.ip}"
            )
        }.onFailure { e ->
            _state.value = _state.value.copy(statusLine = "Pairing failed: ${e.message}")
        }
    }

    fun runQuery() {
        val q = _state.value.query.trim()
        if (q.isEmpty() || _state.value.asking) return
        _state.value = _state.value.copy(asking = true, answer = "", results = emptyList())
        viewModelScope.launch {
            ask.ask(q) { p ->
                _state.value = _state.value.copy(results = p.hits, answer = p.answer)
            }
            _state.value = _state.value.copy(asking = false)
        }
    }

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
