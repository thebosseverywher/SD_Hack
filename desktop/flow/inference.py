"""Inference abstraction with execution-provider (EP) switching.

This is the single interface used to run an ONNX model on either the
**QNN execution provider (NPU)** or the **CPU execution provider**, and it
powers the telemetry NPU<->CPU toggle (§1.0, §6.3).

Out of the box on a bare machine there is no NPU and possibly no model file,
so everything degrades gracefully:

  * If onnxruntime is missing -> :class:`OnnxModel.load` returns ``None``-like
    behaviour by raising a clear, catchable error only when actually used.
  * If the QNN EP / backend is unavailable -> we transparently fall back to CPU
    and report ``ep == "CPU"`` so the UI shows the truth.

``QNN`` extension points are marked with ``# TODO(QNN)``.
"""

from __future__ import annotations

import time
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from . import config
from .telemetry import TELEMETRY

EP_CPU = "CPU"
EP_QNN = "QNN"

try:  # onnxruntime is a core dep, but guard anyway for graceful degradation.
    import onnxruntime as ort  # type: ignore
    _ORT_AVAILABLE = True
except Exception:  # pragma: no cover - only if install is broken
    ort = None  # type: ignore
    _ORT_AVAILABLE = False


def available_providers() -> List[str]:
    """Return the ORT providers available in this process (empty if no ORT)."""
    if not _ORT_AVAILABLE:
        return []
    try:
        return list(ort.get_available_providers())
    except Exception:
        return []


def qnn_available() -> bool:
    """True if the QNN EP appears usable (provider present + backend configured)."""
    if "QNNExecutionProvider" not in available_providers():
        return False
    # The QNN EP also needs the HTP backend .so/.dll path. We treat a configured
    # backend path as the signal it can actually run on the NPU.
    return bool(config.QNN_BACKEND_PATH)


def _provider_spec(ep: str) -> List[Any]:
    """Translate our EP label into ORT's provider list (with options)."""
    if ep == EP_QNN and qnn_available():
        # TODO(QNN): tune htp_performance_mode, profiling, context-binary caching.
        return [
            (
                "QNNExecutionProvider",
                {
                    "backend_path": config.QNN_BACKEND_PATH,
                    "htp_performance_mode": "high_performance",
                },
            ),
            "CPUExecutionProvider",  # safety net inside ORT
        ]
    return ["CPUExecutionProvider"]


class OnnxModel:
    """A loaded ONNX model that can run on a chosen EP and time itself."""

    def __init__(self, session: "ort.InferenceSession", ep: str, name: str) -> None:
        self._session = session
        self.ep = ep
        self.name = name
        self._input_names = [i.name for i in session.get_inputs()]
        self._output_names = [o.name for o in session.get_outputs()]

    @classmethod
    def load(cls, model_path: str, ep: str = EP_CPU, *, name: Optional[str] = None,
             warmup: bool = False) -> "OnnxModel":
        """Build an ORT session for ``model_path`` on the requested EP.

        Falls back to CPU if QNN is requested but unavailable. Raises a clear
        RuntimeError if onnxruntime itself is not installed.
        """
        if not _ORT_AVAILABLE:
            raise RuntimeError(
                "onnxruntime is not installed; cannot build an ONNX session"
            )
        requested = ep
        providers = _provider_spec(ep)
        effective_ep = EP_QNN if (ep == EP_QNN and qnn_available()) else EP_CPU

        so = ort.SessionOptions()
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        session = ort.InferenceSession(model_path, sess_options=so, providers=providers)
        model = cls(session, effective_ep, name or model_path)

        if requested == EP_QNN and effective_ep == EP_CPU:
            # Be honest in the telemetry/logs about the silent fallback.
            TELEMETRY.record("ep_fallback", EP_CPU, 0.0,
                             extra={"requested": EP_QNN, "reason": "QNN unavailable"})

        if warmup:
            model._warmup()
        return model

    def _warmup(self) -> None:
        """Run one dummy pass so the first real call is not penalised."""
        try:
            feeds = {}
            for inp in self._session.get_inputs():
                shape = [d if isinstance(d, int) and d > 0 else 1 for d in inp.shape]
                dtype = np.float32
                if "int64" in (inp.type or ""):
                    dtype = np.int64
                elif "int32" in (inp.type or ""):
                    dtype = np.int32
                feeds[inp.name] = np.zeros(shape, dtype=dtype)
            self._session.run(None, feeds)
        except Exception:
            # Warmup is best-effort.
            pass

    def run(self, inputs: Dict[str, np.ndarray]) -> Tuple[List[np.ndarray], float]:
        """Run the model, returning (outputs, latency_ms) and emitting telemetry."""
        t0 = time.perf_counter()
        outputs = self._session.run(self._output_names, inputs)
        latency_ms = (time.perf_counter() - t0) * 1000.0
        TELEMETRY.record(f"onnx:{self.name}", self.ep, latency_ms)
        return outputs, latency_ms


def benchmark(model_path: str, sample_inputs: Dict[str, np.ndarray],
              eps: Optional[List[str]] = None, runs: int = 3) -> Dict[str, float]:
    """Run the same model on each EP and return {ep: median_ms}.

    Used by the telemetry NPU/CPU toggle to show the latency delta. On a bare
    CPU-only machine this returns CPU-vs-CPU (QNN omitted), which is still a
    valid demonstration of the switching path.
    """
    eps = eps or ([EP_QNN, EP_CPU] if qnn_available() else [EP_CPU])
    out: Dict[str, float] = {}
    for ep in eps:
        try:
            model = OnnxModel.load(model_path, ep=ep, warmup=True)
        except Exception:
            continue
        times: List[float] = []
        for _ in range(max(1, runs)):
            _, ms = model.run(sample_inputs)
            times.append(ms)
        times.sort()
        out[model.ep] = times[len(times) // 2]
    return out
