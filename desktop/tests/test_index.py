"""Index tests — ingest + search round-trip, dedupe, secret rejection.

Uses deterministic synthetic vectors so the test does not require the heavy
embedding model. Works whether sqlite-vec is present (vec0) or the numpy
fallback is active.
"""

import numpy as np
import pytest

from flow.config import TEXT_DIM
from flow.index import Index
from flow.protocol import Item, new_id


def _unit(seed: int, dim: int = TEXT_DIM) -> np.ndarray:
    rng = np.random.default_rng(seed)
    v = rng.standard_normal(dim).astype(np.float32)
    return v / np.linalg.norm(v)


@pytest.fixture()
def index(tmp_path):
    idx = Index(str(tmp_path / "t.db"))
    yield idx
    idx.close()


def _item(text="doc", source="files", type="doc", fields=None):
    return Item(id=new_id(), device_id="devA", source=source, ts=1,
                text=text, type=type, fields=fields or {})


def test_ingest_and_search_topk(index):
    target_vec = _unit(1)
    target = _item(text="the wifi password is hunter2")
    index.ingest(target, text_vec=target_vec)
    for s in range(2, 12):
        index.ingest(_item(text=f"unrelated {s}"), text_vec=_unit(s))

    hits = index.search_text(target_vec, k=5)
    assert hits, "expected at least one hit"
    assert hits[0].item_id == target.id  # known item is top-1 for its own query


def test_dedupe_is_noop(index):
    it = _item(text="same content")
    v = _unit(3)
    first = index.ingest(it, text_vec=v)
    assert first == it.id
    dup = Item(id=new_id(), device_id="devA", source="files", ts=1,
               text="same content", type="doc", fields={})
    assert index.ingest(dup, text_vec=v) is None
    assert index.count() == 1


def test_password_flagged_never_stored(index):
    secret = _item(text="topsecret", fields={"is_password": True})
    assert index.ingest(secret, text_vec=_unit(4)) is None
    assert index.count() == 0


def test_filters(index):
    index.ingest(_item(text="a", source="files", type="doc"), text_vec=_unit(5))
    index.ingest(_item(text="b", source="trove", type="wifi"), text_vec=_unit(6))
    hits = index.search_text(_unit(6), k=5, filters={"source": "trove"})
    assert all(h.source == "trove" for h in hits)
