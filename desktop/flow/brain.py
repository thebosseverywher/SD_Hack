"""Brain — Ask (federated RAG), Surface (rule engine), Act (OS-intent stub).

Ask flow (protocol.md §"Query -> answer flow", spec §5.1):
  1. embed the query (text->384; also CLIP-text->512 when image search helps),
  2. search the local index in both spaces + fan-out QUERY to peers,
  3. fuse local + peer hits with RRF,
  4. build a cited RAG context, run the LLM (or extractive fallback),
  5. return ``{answer, sources:[{item_id, device_id, file_ref}]}``.

Each stage is timed via :mod:`flow.telemetry` for the UI panel.
"""

from __future__ import annotations

import asyncio
import time
from typing import Any, Dict, List, Optional

import numpy as np

from . import config, embeddings, llm
from .fusion import fuse
from .index import Index
from .protocol import Hit, Surface
from .telemetry import TELEMETRY


class Brain:
    def __init__(self, index: Index, federation=None, device_id: Optional[str] = None) -> None:
        self.index = index
        self.federation = federation
        self.device_id = device_id or config.DEVICE_ID or "flow-desktop"
        self.rules = SurfaceRules()

    # ---- local search (also used as the federation search_fn) ----
    def local_search(self, text: str, top_k: int = config.TOP_K,
                     filters: Optional[Dict[str, Any]] = None) -> List[Hit]:
        """Search the local index in both spaces and return fused local hits."""
        hit_lists: List[List[Hit]] = []

        with TELEMETRY.stage("embed_query", ep="CPU"):
            tvec = embeddings.embed_text([text])
        if tvec.shape[0] and np.any(tvec[0]):
            with TELEMETRY.stage("search_text", ep="CPU"):
                hit_lists.append(self.index.search_text(tvec[0], top_k, filters))

        # CLIP-text -> image space (helps "show me the photo of ..." queries).
        if embeddings.image_enabled():
            ivec = embeddings.embed_clip_text([text])
            if ivec.shape[0] and np.any(ivec[0]):
                with TELEMETRY.stage("search_image", ep="CPU"):
                    hit_lists.append(self.index.search_image(ivec[0], top_k, filters))

        if not hit_lists:
            return []
        return fuse(hit_lists, top_k=top_k)

    # ---- Ask ----
    def ask(self, text: str, top_k: int = config.TOP_K,
            filters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Synchronous Ask. Runs federation fan-out if an event loop is available."""
        t_start = time.perf_counter()
        local_hits = self.local_search(text, top_k, filters)

        peer_hit_lists: List[List[Hit]] = []
        if self.federation is not None:
            peer_results = self._gather_peers(text, top_k)
            for res in peer_results:
                peer_hit_lists.append(res.hits)

        with TELEMETRY.stage("fuse", ep="CPU"):
            fused = fuse([local_hits, *peer_hit_lists], top_k=top_k)

        answer, sources = self._compose(text, fused)
        total_ms = (time.perf_counter() - t_start) * 1000.0
        TELEMETRY.record("ask_total", TELEMETRY.active_ep, total_ms)

        return {
            "answer": answer,
            "sources": sources,
            "hits": [h.to_dict() for h in fused],
            "telemetry": TELEMETRY.snapshot(),
        }

    def _gather_peers(self, text: str, top_k: int):
        if self.federation is None:
            return []
        try:
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                loop = None
            if loop is not None:
                # Called from within a running loop: schedule + wait briefly.
                fut = asyncio.run_coroutine_threadsafe(
                    self.federation.query_peers(text, top_k), loop)
                return fut.result(timeout=(config.QUERY_TIMEOUT_MS / 1000.0) + 0.5)
            # No loop running: spin one up just for the fan-out.
            return asyncio.run(self.federation.query_peers(text, top_k))
        except Exception:
            return []  # partial / no peers is fine

    async def ask_async(self, text: str, top_k: int = config.TOP_K,
                        filters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Async Ask for use inside the server event loop."""
        t_start = time.perf_counter()
        local_hits = self.local_search(text, top_k, filters)
        peer_hit_lists: List[List[Hit]] = []
        if self.federation is not None:
            results = await self.federation.query_peers(text, top_k)
            peer_hit_lists = [r.hits for r in results]
        with TELEMETRY.stage("fuse", ep="CPU"):
            fused = fuse([local_hits, *peer_hit_lists], top_k=top_k)
        answer, sources = self._compose(text, fused)
        TELEMETRY.record("ask_total", TELEMETRY.active_ep,
                         (time.perf_counter() - t_start) * 1000.0)
        return {"answer": answer, "sources": sources,
                "hits": [h.to_dict() for h in fused],
                "telemetry": TELEMETRY.snapshot()}

    # ---- answer composition (RAG) ----
    def _compose(self, question: str, hits: List[Hit]):
        sources = [
            {"item_id": h.item_id, "device_id": h.device_id,
             "file_ref": self._file_ref(h)}
            for h in hits
        ]
        if not hits:
            return ("I couldn't find that in your indexed items.", sources)

        backend = llm.get_backend()
        if isinstance(backend, llm.ExtractiveBackend):
            with TELEMETRY.stage("llm_extractive", ep="CPU"):
                answer = backend.answer_from_hits(question, hits)
            return (answer, sources)

        # Generative backend (llama.cpp / future QNN Genie).
        context = llm.build_context(hits)
        prompt = llm.load_rag_prompt().format(context=context, question=question)
        t0 = time.perf_counter()
        answer = backend.generate(prompt, max_tokens=512)
        ms = (time.perf_counter() - t0) * 1000.0
        TELEMETRY.record("llm_generate", TELEMETRY.active_ep, ms,
                         extra={"backend": backend.name})
        return (answer, sources)

    def _file_ref(self, hit: Hit) -> Optional[str]:
        item = self.index.get_item(hit.item_id)
        return item.file_ref if item else None

    # ---- Surface ----
    def surface(self, event: str, payload: Dict[str, Any]) -> Optional[Surface]:
        """Run the rule engine for a triggering event. Returns a Surface or None."""
        return self.rules.evaluate(event, payload, self.index)

    # ---- Act (stub) ----
    def act(self, answer: Dict[str, Any]) -> Dict[str, Any]:
        """Map an answer to an OS action. Stub: emits a calendar-event intent.

        On Windows/Android a real implementation would call the OS calendar
        intent (spec §5.3). Here we return the structured action so the UI /
        caller can perform it, keeping the engine OS-agnostic.
        """
        text = answer.get("answer", "") if isinstance(answer, dict) else str(answer)
        return {
            "action": "create_calendar_event",
            "status": "stub",
            "event": {"title": text[:120], "when": None, "notes": text},
            "note": "OS calendar intent not wired on this platform (stub).",
        }


# ---------------------------------------------------------------------------
# Surface rule engine (tiny; ships one demo rule — spec §5.2)
# ---------------------------------------------------------------------------
class SurfaceRules:
    """Rules as {trigger, condition, action}; ships the parking-recall demo."""

    def evaluate(self, event: str, payload: Dict[str, Any],
                 index: Index) -> Optional[Surface]:
        # Demo rule: leaving the mall -> recall the most recent parking photo.
        if event == "geofence_exit" and payload.get("place_type") == "mall":
            tvec = embeddings.embed_text(["where did I park my car parking spot level"])
            hits = index.search_text(tvec[0], k=1, filters={"type": "parking"}) \
                if tvec.shape[0] else []
            if hits:
                h = hits[0]
                return Surface(event="parking_recall", payload={
                    "title": "You're leaving the mall — here's your parking spot",
                    "item_id": h.item_id, "device_id": h.device_id,
                    "fields": h.fields, "thumb_b64": h.thumb_b64,
                    "text": h.text,
                })
        return None
