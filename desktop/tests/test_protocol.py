"""Protocol round-trip tests — to_dict/from_dict for Item, Hit, and messages."""

import pytest

from flow.protocol import (
    Item, Hit, Hello, Query, Results, Fetch, FetchResult, Surface,
    decode_message, new_id,
)


def test_item_roundtrip():
    item = Item(id=new_id(), device_id="dev1", source="files", ts=123,
                text="hello world", type="doc", app_context="a.pdf",
                fields={"page": 2}, thumb_b64=None, file_ref="/tmp/a.pdf")
    d = item.to_dict()
    # field names must match protocol.md exactly
    assert set(d.keys()) == {"id", "device_id", "source", "ts", "app_context",
                             "text", "type", "fields", "thumb_b64", "file_ref"}
    back = Item.from_dict(d)
    assert back == item


def test_hit_roundtrip_and_from_item():
    item = Item(id="i1", device_id="dev1", source="trove", ts=1, text="t",
                type="wifi", fields={"ssid": "x"})
    hit = Hit.from_item(item, 0.9)
    d = hit.to_dict()
    assert set(d.keys()) == {"item_id", "device_id", "score", "source", "type",
                             "text", "fields", "thumb_b64"}
    assert Hit.from_dict(d) == hit


@pytest.mark.parametrize("msg", [
    Hello(device_id="d", name="laptop", caps={"has_llm": True}),
    Query(query_id="q1", text="find wifi", top_k=8),
    Results(query_id="q1", device_id="d",
            hits=[Hit(item_id="i", device_id="d", score=0.5, source="files",
                      type="doc", text="snippet")]),
    Fetch(item_id="i1"),
    FetchResult(item_id="i1", mime="image/png", blob_b64="AAA="),
    Surface(event="parking_recall", payload={"item_id": "i1"}),
])
def test_message_roundtrip_via_decode(msg):
    decoded = decode_message(msg.to_dict())
    assert decoded == msg


def test_decode_rejects_unknown_and_version():
    with pytest.raises(ValueError):
        decode_message({"type": "NOPE", "v": 1})
    with pytest.raises(ValueError):
        decode_message({"type": "QUERY", "v": 999, "query_id": "q", "text": "x"})
