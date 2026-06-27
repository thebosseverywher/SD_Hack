"""LLM backend — pluggable, with an always-available extractive fallback.

Order of preference:
  1. **llama.cpp** (``llama-cpp-python``) if a GGUF model path is configured
     via ``FLOW_LLM_MODEL`` / config — local, CPU-capable generation.
  2. **Extractive fallback** — composes a cited answer directly from the
     retrieved snippets so the engine ALWAYS produces an answer on a bare
     machine with no model downloaded.

On-device target (documented, not required here): **Genie (QNN GenAI)** running
an AI-Hub-precompiled INT4 Llama on the NPU, or ORT-GenAI + QNN EP. See the
``# TODO(QNN)`` extension points and the README.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Dict, Iterator, List, Optional

from . import config
from .protocol import Hit
from .telemetry import TELEMETRY

# ---------------------------------------------------------------------------
# Prompt loading
# ---------------------------------------------------------------------------
def _find_prompt(name: str) -> Optional[Path]:
    here = Path(__file__).resolve()
    for parent in [here.parent, *here.parents]:
        cand = parent / "shared" / "prompts" / name
        if cand.is_file():
            return cand
    return None


_DEFAULT_RAG_PROMPT = (
    "You are Flow, an on-device personal-memory assistant. Answer the user's "
    "question USING ONLY the numbered context items below. Cite item ids in "
    "square brackets. If the answer is not present, say you couldn't find it.\n\n"
    "Context items:\n{context}\n\nQuestion: {question}\n\nAnswer (with citations):\n"
)


def load_rag_prompt() -> str:
    p = _find_prompt("rag_answer.txt")
    if p is not None:
        try:
            return p.read_text(encoding="utf-8")
        except OSError:
            pass
    return _DEFAULT_RAG_PROMPT


# ---------------------------------------------------------------------------
# Context builder (shared by both backends)
# ---------------------------------------------------------------------------
def build_context(hits: List[Hit], max_chars: int = 3500) -> str:
    """Render hits into a numbered, citable context block (truncated to window)."""
    lines: List[str] = []
    used = 0
    for i, h in enumerate(hits, start=1):
        field_str = ""
        if h.fields:
            field_str = " | fields: " + ", ".join(
                f"{k}={v}" for k, v in h.fields.items() if not str(k).startswith("_")
            )
        snippet = (h.text or "").strip().replace("\n", " ")
        entry = f"[{i}] (id={h.item_id}, device={h.device_id}, source={h.source}, type={h.type}) {snippet}{field_str}"
        if used + len(entry) > max_chars:
            break
        lines.append(entry)
        used += len(entry)
    return "\n".join(lines) if lines else "(no items)"


# ---------------------------------------------------------------------------
# Backend interface
# ---------------------------------------------------------------------------
class LLMBackend:
    name = "base"
    is_generative = False

    def generate(self, prompt: str, max_tokens: int = 512,
                 stop: Optional[List[str]] = None, stream: bool = False):
        raise NotImplementedError


class LlamaCppBackend(LLMBackend):
    """Local GGUF model via llama-cpp-python (CPU by default)."""

    name = "llama.cpp"
    is_generative = True

    def __init__(self, model_path: str, n_ctx: int = 4096) -> None:
        from llama_cpp import Llama  # type: ignore

        # TODO(QNN): swap this construction for a Genie/ORT-GenAI session that
        # loads an AI-Hub-precompiled INT4 model onto the NPU. Keep the same
        # generate() signature so brain.py is unchanged.
        self._llm = Llama(model_path=model_path, n_ctx=n_ctx, verbose=False)

    def generate(self, prompt: str, max_tokens: int = 512,
                 stop: Optional[List[str]] = None, stream: bool = False):
        stop = stop or ["\n\nQuestion:", "</s>"]
        if stream:
            return self._stream(prompt, max_tokens, stop)
        out = self._llm(prompt, max_tokens=max_tokens, stop=stop, temperature=0.2)
        text = out["choices"][0]["text"]
        usage = out.get("usage", {})
        toks = int(usage.get("completion_tokens", 0))
        TELEMETRY.record("llm_generate", TELEMETRY.active_ep, 0.0, tokens_s=0.0,
                         extra={"backend": self.name, "tokens": toks})
        return text.strip()

    def _stream(self, prompt: str, max_tokens: int, stop: List[str]) -> Iterator[str]:
        for chunk in self._llm(prompt, max_tokens=max_tokens, stop=stop,
                               temperature=0.2, stream=True):
            yield chunk["choices"][0]["text"]


class ExtractiveBackend(LLMBackend):
    """No-model fallback: compose a grounded answer from the top snippets.

    Not a language model — it stitches the most relevant snippets / structured
    fields into a short, cited answer. Guarantees the engine always answers.
    """

    name = "extractive"
    is_generative = False

    # Keys we treat as "directly answering" when present.
    _PRIORITY_FIELDS = ["ssid", "pass", "password", "amount", "when", "date",
                        "serial", "where", "spot", "level"]

    def generate(self, prompt: str, max_tokens: int = 512,
                 stop: Optional[List[str]] = None, stream: bool = False):
        # The prompt embeds the rendered context; we parse it back rather than
        # re-plumbing, but brain.py calls answer_from_hits() directly for clarity.
        return prompt  # not used directly; see answer_from_hits

    def answer_from_hits(self, question: str, hits: List[Hit]) -> str:
        if not hits:
            return "I couldn't find that in your indexed items."
        top = hits[0]
        parts: List[str] = []

        # 1) If the strongest hit has a priority structured field, lead with it.
        field_hit = self._first_priority_field(hits)
        if field_hit is not None:
            h, key, val = field_hit
            parts.append(f"{key}: {val} [1]")

        # 2) Add the best snippet(s).
        snippet = (top.text or "").strip().replace("\n", " ")
        if snippet:
            snippet = snippet[:300]
            parts.append(f"{snippet} [1]")

        # 3) Mention a second corroborating item if distinct.
        if len(hits) > 1:
            second = (hits[1].text or "").strip().replace("\n", " ")[:160]
            if second and second[:40] not in snippet:
                parts.append(f"Also: {second} [2]")

        answer = " ".join(parts).strip()
        return answer or "I couldn't find that in your indexed items."

    def _first_priority_field(self, hits: List[Hit]):
        for h in hits[:3]:
            for key in self._PRIORITY_FIELDS:
                if h.fields and key in h.fields and h.fields[key]:
                    return (h, key, h.fields[key])
        return None


# ---------------------------------------------------------------------------
# Selection
# ---------------------------------------------------------------------------
_BACKEND: Optional[LLMBackend] = None


def get_backend() -> LLMBackend:
    """Return the active backend (cached). llama.cpp if configured, else extractive."""
    global _BACKEND
    if _BACKEND is not None:
        return _BACKEND
    model_path = config.LLM_MODEL_PATH
    if model_path and Path(model_path).expanduser().is_file():
        try:
            _BACKEND = LlamaCppBackend(str(Path(model_path).expanduser()))
            return _BACKEND
        except Exception:
            # llama-cpp not installed or model failed to load -> extractive.
            pass
    _BACKEND = ExtractiveBackend()
    return _BACKEND


def reset_backend() -> None:
    """Force re-selection (used by tests)."""
    global _BACKEND
    _BACKEND = None
