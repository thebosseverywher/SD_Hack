"""Wire protocol — dataclasses for Item, Hit and every message type.

Field names match ``shared/protocol.md`` EXACTLY so the desktop engine and the
Android app are wire-compatible. Every message is a JSON object of the form::

    { "type": <string>, "v": 1, ...payload }

and is carried inside the AEAD-encrypted frame (see ``crypto`` and ``federation``).
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional

from .config import PROTOCOL_VERSION, TOP_K

# ---------------------------------------------------------------------------
# Message type constants
# ---------------------------------------------------------------------------
HELLO = "HELLO"
QUERY = "QUERY"
RESULTS = "RESULTS"
FETCH = "FETCH"
FETCH_RESULT = "FETCH_RESULT"
SURFACE = "SURFACE"

MESSAGE_TYPES = {HELLO, QUERY, RESULTS, FETCH, FETCH_RESULT, SURFACE}


def new_id() -> str:
    """Generate a fresh UUID4 string (used for item ids and query ids)."""
    return str(uuid.uuid4())


# ---------------------------------------------------------------------------
# Core records
# ---------------------------------------------------------------------------
@dataclass
class Item:
    """The unit stored in every node's local index (see protocol.md §Item)."""

    id: str
    device_id: str
    source: str            # trove|trail|files|sieve|relay|threads|audio
    ts: int                # unix seconds
    text: str
    type: str              # wifi|parking|receipt|serial|poster|event|doc|activity|note|contact|other
    app_context: Optional[str] = None
    fields: Dict[str, Any] = field(default_factory=dict)
    thumb_b64: Optional[str] = None
    file_ref: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "device_id": self.device_id,
            "source": self.source,
            "ts": int(self.ts),
            "app_context": self.app_context,
            "text": self.text,
            "type": self.type,
            "fields": dict(self.fields or {}),
            "thumb_b64": self.thumb_b64,
            "file_ref": self.file_ref,
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Item":
        return cls(
            id=d.get("id") or new_id(),
            device_id=d["device_id"],
            source=d["source"],
            ts=int(d.get("ts", 0)),
            app_context=d.get("app_context"),
            text=d.get("text", "") or "",
            type=d.get("type", "other") or "other",
            fields=dict(d.get("fields") or {}),
            thumb_b64=d.get("thumb_b64"),
            file_ref=d.get("file_ref"),
        )


@dataclass
class Hit:
    """A single match returned in answer to a QUERY (see protocol.md §Hit)."""

    item_id: str
    device_id: str
    score: float
    source: str
    type: str
    text: str
    fields: Dict[str, Any] = field(default_factory=dict)
    thumb_b64: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "item_id": self.item_id,
            "device_id": self.device_id,
            "score": float(self.score),
            "source": self.source,
            "type": self.type,
            "text": self.text,
            "fields": dict(self.fields or {}),
            "thumb_b64": self.thumb_b64,
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Hit":
        return cls(
            item_id=d["item_id"],
            device_id=d["device_id"],
            score=float(d.get("score", 0.0)),
            source=d.get("source", "other"),
            type=d.get("type", "other"),
            text=d.get("text", "") or "",
            fields=dict(d.get("fields") or {}),
            thumb_b64=d.get("thumb_b64"),
        )

    @classmethod
    def from_item(cls, item: Item, score: float) -> "Hit":
        return cls(
            item_id=item.id,
            device_id=item.device_id,
            score=float(score),
            source=item.source,
            type=item.type,
            text=item.text,
            fields=dict(item.fields or {}),
            thumb_b64=item.thumb_b64,
        )


# ---------------------------------------------------------------------------
# Messages
# ---------------------------------------------------------------------------
@dataclass
class Hello:
    """Post-pair handshake. ``caps`` = {tops, has_llm, battery}."""

    device_id: str
    name: str
    caps: Dict[str, Any] = field(default_factory=dict)
    v: int = PROTOCOL_VERSION

    def to_dict(self) -> Dict[str, Any]:
        return {"type": HELLO, "v": self.v, "device_id": self.device_id,
                "name": self.name, "caps": dict(self.caps or {})}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Hello":
        return cls(device_id=d["device_id"], name=d.get("name", ""),
                   caps=dict(d.get("caps") or {}), v=int(d.get("v", PROTOCOL_VERSION)))


@dataclass
class Query:
    """Federated search request, A -> peers."""

    query_id: str
    text: str
    top_k: int = TOP_K
    v: int = PROTOCOL_VERSION

    def to_dict(self) -> Dict[str, Any]:
        return {"type": QUERY, "v": self.v, "query_id": self.query_id,
                "text": self.text, "top_k": int(self.top_k)}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Query":
        return cls(query_id=d["query_id"], text=d.get("text", ""),
                   top_k=int(d.get("top_k", TOP_K)), v=int(d.get("v", PROTOCOL_VERSION)))


@dataclass
class Results:
    """Per-node top-k matches, peer -> A."""

    query_id: str
    device_id: str
    hits: List[Hit] = field(default_factory=list)
    v: int = PROTOCOL_VERSION

    def to_dict(self) -> Dict[str, Any]:
        return {"type": RESULTS, "v": self.v, "query_id": self.query_id,
                "device_id": self.device_id, "hits": [h.to_dict() for h in self.hits]}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Results":
        return cls(query_id=d["query_id"], device_id=d["device_id"],
                   hits=[Hit.from_dict(h) for h in d.get("hits", [])],
                   v=int(d.get("v", PROTOCOL_VERSION)))


@dataclass
class Fetch:
    """Request the full file for an opened result, A -> peer."""

    item_id: str
    v: int = PROTOCOL_VERSION

    def to_dict(self) -> Dict[str, Any]:
        return {"type": FETCH, "v": self.v, "item_id": self.item_id}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Fetch":
        return cls(item_id=d["item_id"], v=int(d.get("v", PROTOCOL_VERSION)))


@dataclass
class FetchResult:
    """The full file, peer -> A."""

    item_id: str
    mime: str
    blob_b64: str
    v: int = PROTOCOL_VERSION

    def to_dict(self) -> Dict[str, Any]:
        return {"type": FETCH_RESULT, "v": self.v, "item_id": self.item_id,
                "mime": self.mime, "blob_b64": self.blob_b64}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "FetchResult":
        return cls(item_id=d["item_id"], mime=d.get("mime", "application/octet-stream"),
                   blob_b64=d.get("blob_b64", ""), v=int(d.get("v", PROTOCOL_VERSION)))


@dataclass
class Surface:
    """Proactive recall notification (push)."""

    event: str
    payload: Dict[str, Any] = field(default_factory=dict)
    v: int = PROTOCOL_VERSION

    def to_dict(self) -> Dict[str, Any]:
        return {"type": SURFACE, "v": self.v, "event": self.event,
                "payload": dict(self.payload or {})}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Surface":
        # protocol.md intends ``payload`` to be a JSON object. The Android side
        # types it as a generic JsonElement, so a peer *could* emit a non-object
        # (array/scalar/null). Degrade gracefully instead of raising on decode:
        # coerce any non-object payload into a wrapped object so the core path
        # never crashes (see protocol findings — SURFACE payload width mismatch).
        raw = d.get("payload")
        if isinstance(raw, dict):
            payload: Dict[str, Any] = dict(raw)
        elif raw is None:
            payload = {}
        else:
            payload = {"value": raw}
        return cls(event=d["event"], payload=payload,
                   v=int(d.get("v", PROTOCOL_VERSION)))


# ---------------------------------------------------------------------------
# Generic (de)serialization helper
# ---------------------------------------------------------------------------
_DECODERS = {
    HELLO: Hello.from_dict,
    QUERY: Query.from_dict,
    RESULTS: Results.from_dict,
    FETCH: Fetch.from_dict,
    FETCH_RESULT: FetchResult.from_dict,
    SURFACE: Surface.from_dict,
}


def decode_message(d: Dict[str, Any]):
    """Decode a JSON dict into the appropriate message dataclass.

    Raises ``ValueError`` for unknown types or major-version mismatch.
    """
    mtype = d.get("type")
    if mtype not in _DECODERS:
        raise ValueError(f"unknown message type: {mtype!r}")
    v = int(d.get("v", PROTOCOL_VERSION))
    if v != PROTOCOL_VERSION:
        # Mismatched majors must refuse (protocol.md §Versioning).
        raise ValueError(f"protocol version mismatch: got {v}, expected {PROTOCOL_VERSION}")
    return _DECODERS[mtype](d)
