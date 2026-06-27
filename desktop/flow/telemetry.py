"""Telemetry — per-stage timing events for the UI panel.

Each inference / pipeline stage emits a structured event::

    { "stage": "embed_text", "ep": "CPU", "ms": 12.4, "tokens_s": 0.0, "ts": ... }

The UI telemetry panel (§6.3) reads ``recent()`` / ``snapshot()`` to show live
latency, tokens/sec and the active execution provider, and to visualise the
NPU<->CPU toggle delta.
"""

from __future__ import annotations

import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass, asdict
from typing import Any, Dict, List, Optional


@dataclass
class StageEvent:
    stage: str
    ep: str            # "CPU" | "QNN" | "n/a"
    ms: float
    tokens_s: float = 0.0
    ts: float = 0.0
    extra: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        d = asdict(self)
        if self.extra is None:
            d.pop("extra")
        return d


class Telemetry:
    """Thread-safe ring buffer of stage events plus per-stage aggregates."""

    def __init__(self, capacity: int = 256) -> None:
        self._capacity = capacity
        self._events: List[StageEvent] = []
        self._lock = threading.Lock()
        # Active execution provider as last selected by the UI toggle / inference.
        self.active_ep: str = "CPU"

    def record(self, stage: str, ep: str, ms: float,
               tokens_s: float = 0.0, extra: Optional[Dict[str, Any]] = None) -> StageEvent:
        ev = StageEvent(stage=stage, ep=ep, ms=float(ms),
                        tokens_s=float(tokens_s), ts=time.time(), extra=extra)
        with self._lock:
            self._events.append(ev)
            if len(self._events) > self._capacity:
                self._events = self._events[-self._capacity:]
        return ev

    @contextmanager
    def stage(self, stage: str, ep: str = "CPU", tokens: int = 0):
        """Context manager that times a block and records a stage event.

        ``tokens`` (if > 0) is used to compute tokens/sec on exit.
        """
        t0 = time.perf_counter()
        try:
            yield
        finally:
            ms = (time.perf_counter() - t0) * 1000.0
            toks = (tokens / (ms / 1000.0)) if (tokens and ms > 0) else 0.0
            self.record(stage, ep, ms, toks)

    def recent(self, n: int = 50) -> List[Dict[str, Any]]:
        with self._lock:
            return [e.to_dict() for e in self._events[-n:]]

    def snapshot(self) -> Dict[str, Any]:
        """Aggregate view for the panel: latest per stage + active EP."""
        with self._lock:
            events = list(self._events)
        latest: Dict[str, Dict[str, Any]] = {}
        for e in events:
            latest[e.stage] = e.to_dict()
        return {
            "active_ep": self.active_ep,
            "stages": latest,
            "events": [e.to_dict() for e in events[-50:]],
        }

    def clear(self) -> None:
        with self._lock:
            self._events.clear()


# Process-wide singleton used across the package.
TELEMETRY = Telemetry()
