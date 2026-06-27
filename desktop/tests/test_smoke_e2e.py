"""End-to-end smoke test: index a temp .txt and ask a question.

Uses the extractive LLM fallback (no model file) so it runs on a bare machine.
If the real sentence-transformers model is unavailable, we monkeypatch
``flow.embeddings.embed_text`` with a tiny deterministic hashing embedder so the
retrieval path still produces a sensible top-1, keeping the test hermetic.
"""

import numpy as np
import pytest

import flow.embeddings as embeddings
from flow.config import TEXT_DIM
from flow.brain import Brain
from flow.index import Index
from flow import llm


def _toy_embed(texts):
    """Deterministic bag-of-words hashing embedder -> normalized 384-d vectors."""
    out = np.zeros((len(texts), TEXT_DIM), dtype=np.float32)
    for i, t in enumerate(texts):
        for tok in t.lower().split():
            out[i, hash(tok) % TEXT_DIM] += 1.0
        n = np.linalg.norm(out[i])
        if n:
            out[i] /= n
    return out


@pytest.fixture(autouse=True)
def _force_extractive():
    llm.reset_backend()
    yield
    llm.reset_backend()


def test_index_txt_and_ask(tmp_path, monkeypatch):
    # Prefer the real embedder; fall back to the toy one if it can't load.
    if not embeddings.text_enabled():
        monkeypatch.setattr(embeddings, "embed_text", _toy_embed)
        monkeypatch.setattr(embeddings, "image_enabled", lambda: False)

    doc = tmp_path / "notes.txt"
    doc.write_text(
        "The office wifi network is FlowGuest and the password is bluebird42.\n"
        "Parking is on level 3 near the elevator.\n"
        "The dentist appointment is on 2026-07-10.\n",
        encoding="utf-8",
    )

    index = Index(str(tmp_path / "smoke.db"))
    from flow.sensors import files as files_sensor
    stats = files_sensor.index_folder(index, str(tmp_path), device_id="devTest")
    assert stats["ingested"] >= 1

    brain = Brain(index, federation=None, device_id="devTest")
    result = brain.ask("what is the office wifi password", top_k=5)

    assert result["answer"]
    assert "couldn't find" not in result["answer"].lower()
    # The wifi chunk should be the cited source.
    assert result["hits"], "expected retrieval hits"
    top_text = result["hits"][0]["text"].lower()
    assert "wifi" in top_text or "bluebird42" in top_text
    index.close()
