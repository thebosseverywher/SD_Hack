"""Federation — peer mesh over encrypted WebSockets + QR pairing (§4).

Each node runs both a WebSocket **server** and **client** (symmetric mesh).
All frames are AEAD-encrypted via :class:`flow.crypto.SecureChannel`; the
plaintext inside is the JSON of a protocol message.

Responsibilities:
  * QR pairing: produce / decode the ``{ip, port, psk, v}`` JSON (protocol.md).
  * Handle inbound QUERY (search local index, reply RESULTS), FETCH/FETCH_RESULT,
    HELLO, SURFACE.
  * Fan-out a QUERY to all peers and collect RESULTS within ``query_timeout_ms``
    (partial results allowed).

The federation layer is transport + routing only; the actual search and answer
composition live in ``brain``. A ``search_fn`` callback is injected to avoid a
circular import.
"""

from __future__ import annotations

import asyncio
import base64
import json
import socket
import time
from dataclasses import dataclass
from typing import Awaitable, Callable, Dict, List, Optional

from . import config
from .crypto import SecureChannel, ReplayError, random_psk
from .protocol import (
    Fetch, FetchResult, Hello, Query, Results, Surface,
    decode_message, FETCH, FETCH_RESULT, HELLO, QUERY, RESULTS, SURFACE,
    new_id, Hit,
)
from .telemetry import TELEMETRY

try:
    import websockets  # type: ignore
    _WS_AVAILABLE = True
except Exception:  # pragma: no cover
    websockets = None  # type: ignore
    _WS_AVAILABLE = False


# Callback signatures injected by brain/server.
SearchFn = Callable[[str, int], List[Hit]]            # (query_text, top_k) -> local hits
FetchFn = Callable[[str], Optional["FetchResult"]]    # (item_id) -> FetchResult or None
SurfaceFn = Callable[[Surface], None]                 # inbound proactive push


# ---------------------------------------------------------------------------
# QR pairing payload
# ---------------------------------------------------------------------------
def local_ip() -> str:
    """Best-effort LAN IP (the address a peer would dial)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def make_pairing_payload(psk: Optional[bytes] = None,
                         ip: Optional[str] = None,
                         port: int = config.WS_PORT) -> Dict[str, object]:
    """Build the QR JSON: ``{ip, port, psk(base64 32B), v}``."""
    psk = psk or random_psk()
    return {
        "ip": ip or local_ip(),
        "port": int(port),
        "psk": base64.b64encode(psk).decode("ascii"),
        "v": config.PROTOCOL_VERSION,
    }


def parse_pairing_payload(data) -> Dict[str, object]:
    """Decode the QR JSON (str or dict) into a normalized dict with raw psk bytes."""
    if isinstance(data, (bytes, str)):
        obj = json.loads(data)
    else:
        obj = dict(data)
    if int(obj.get("v", config.PROTOCOL_VERSION)) != config.PROTOCOL_VERSION:
        raise ValueError("pairing protocol version mismatch")
    psk = base64.b64decode(obj["psk"])
    if len(psk) != config.PSK_BYTES:
        raise ValueError("bad psk length")
    return {"ip": obj["ip"], "port": int(obj["port"]), "psk": psk,
            "v": int(obj.get("v", config.PROTOCOL_VERSION))}


def make_qr_png(payload: Optional[Dict[str, object]] = None) -> bytes:
    """Render the pairing payload as a PNG (bytes)."""
    import io
    import qrcode

    payload = payload or make_pairing_payload()
    img = qrcode.make(json.dumps(payload))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


# ---------------------------------------------------------------------------
# Peer connections
# ---------------------------------------------------------------------------
@dataclass
class Peer:
    device_id: str
    name: str
    ws: object  # websocket connection
    channel: SecureChannel
    caps: Dict[str, object]


class Federation:
    """Symmetric peer-mesh node: WS server + client, with one shared PSK.

    For the hackathon demo a single PSK is shared across the mesh (the QR-paired
    key). Each connection gets its own :class:`SecureChannel` instance so replay
    windows are per-connection.
    """

    def __init__(self, device_id: str, name: str,
                 psk: Optional[bytes] = None,
                 search_fn: Optional[SearchFn] = None,
                 fetch_fn: Optional[FetchFn] = None,
                 surface_fn: Optional[SurfaceFn] = None,
                 port: int = config.WS_PORT) -> None:
        self.device_id = device_id
        self.name = name
        self.port = port
        self.psk = psk or random_psk()
        self.search_fn = search_fn
        self.fetch_fn = fetch_fn
        self.surface_fn = surface_fn

        self._peers: Dict[str, Peer] = {}
        self._server = None
        # query_id -> {device_id: Results}
        self._pending: Dict[str, Dict[str, Results]] = {}
        self._pending_events: Dict[str, asyncio.Event] = {}
        # item_id correlation for FETCH responses
        self._fetch_waiters: Dict[str, asyncio.Future] = {}

    # ---- pairing helpers ----
    def pairing_payload(self) -> Dict[str, object]:
        return make_pairing_payload(self.psk, port=self.port)

    def set_psk(self, psk: bytes) -> None:
        self.psk = psk

    # ---- server ----
    async def start_server(self) -> None:
        if not _WS_AVAILABLE:
            raise RuntimeError("websockets not installed; cannot start server")
        self._server = await websockets.serve(self._on_connection, "0.0.0.0", self.port)
        TELEMETRY.record("federation_server", "n/a", 0.0,
                         extra={"port": self.port, "device_id": self.device_id})

    async def _on_connection(self, ws) -> None:
        channel = SecureChannel(self.psk)
        peer_id: Optional[str] = None
        try:
            async for raw in ws:
                msg = self._decrypt(channel, raw)
                if msg is None:
                    continue
                peer_id = await self._dispatch(ws, channel, msg, peer_id)
        except Exception:
            pass
        finally:
            if peer_id and peer_id in self._peers:
                del self._peers[peer_id]

    # ---- client ----
    async def connect(self, ip: str, port: int, psk: Optional[bytes] = None) -> Peer:
        if not _WS_AVAILABLE:
            raise RuntimeError("websockets not installed; cannot connect")
        psk = psk or self.psk
        self.psk = psk  # adopt the paired key for the mesh
        channel = SecureChannel(psk)
        uri = f"ws://{ip}:{port}"
        ws = await websockets.connect(uri, max_size=16 * 1024 * 1024)

        # Handshake: send HELLO.
        hello = Hello(device_id=self.device_id, name=self.name,
                      caps={"has_llm": True, "tops": 0, "battery": None})
        await ws.send(channel.seal_b64(json.dumps(hello.to_dict()).encode()))

        peer = Peer(device_id=f"pending:{ip}:{port}", name="", ws=ws,
                    channel=channel, caps={})
        self._peers[peer.device_id] = peer
        # Pump inbound frames from this connection in the background.
        asyncio.ensure_future(self._client_pump(peer))
        return peer

    async def _client_pump(self, peer: Peer) -> None:
        try:
            async for raw in peer.ws:
                msg = self._decrypt(peer.channel, raw)
                if msg is None:
                    continue
                await self._dispatch(peer.ws, peer.channel, msg, peer.device_id)
        except Exception:
            pass

    # ---- crypto plumbing ----
    def _decrypt(self, channel: SecureChannel, raw):
        try:
            if isinstance(raw, (bytes, bytearray)):
                pt = channel.open(bytes(raw))
            else:
                pt = channel.open_b64(raw)
        except ReplayError:
            TELEMETRY.record("replay_rejected", "n/a", 0.0)
            return None
        except Exception:
            TELEMETRY.record("frame_rejected", "n/a", 0.0)
            return None
        try:
            return decode_message(json.loads(pt.decode("utf-8")))
        except Exception:
            return None

    async def _send(self, ws, channel: SecureChannel, msg_dict: dict) -> None:
        await ws.send(channel.seal_b64(json.dumps(msg_dict).encode()))

    # ---- dispatch ----
    async def _dispatch(self, ws, channel: SecureChannel, msg, peer_id):
        if isinstance(msg, Hello):
            self._peers[msg.device_id] = Peer(
                device_id=msg.device_id, name=msg.name, ws=ws,
                channel=channel, caps=msg.caps,
            )
            return msg.device_id

        if isinstance(msg, Query):
            hits = self.search_fn(msg.text, msg.top_k) if self.search_fn else []
            res = Results(query_id=msg.query_id, device_id=self.device_id, hits=hits)
            await self._send(ws, channel, res.to_dict())
            return peer_id

        if isinstance(msg, Results):
            bucket = self._pending.get(msg.query_id)
            if bucket is not None:
                bucket[msg.device_id] = msg
                ev = self._pending_events.get(msg.query_id)
                if ev is not None:
                    ev.set()
            return peer_id

        if isinstance(msg, Fetch):
            fr = self.fetch_fn(msg.item_id) if self.fetch_fn else None
            if fr is None:
                fr = FetchResult(item_id=msg.item_id, mime="application/octet-stream",
                                 blob_b64="")
            await self._send(ws, channel, fr.to_dict())
            return peer_id

        if isinstance(msg, FetchResult):
            fut = self._fetch_waiters.get(msg.item_id)
            if fut is not None and not fut.done():
                fut.set_result(msg)
            return peer_id

        if isinstance(msg, Surface):
            if self.surface_fn:
                self.surface_fn(msg)
            return peer_id

        return peer_id

    # ---- fan-out QUERY ----
    async def query_peers(self, text: str, top_k: int = config.TOP_K,
                          timeout_ms: int = config.QUERY_TIMEOUT_MS) -> List[Results]:
        """Broadcast a QUERY to all peers; collect RESULTS within timeout.

        Returns whatever arrived before the deadline (partial allowed).
        """
        if not self._peers:
            return []
        query_id = new_id()
        self._pending[query_id] = {}
        self._pending_events[query_id] = asyncio.Event()
        q = Query(query_id=query_id, text=text, top_k=top_k)

        targets = list(self._peers.values())
        for peer in targets:
            try:
                await self._send(peer.ws, peer.channel, q.to_dict())
            except Exception:
                continue

        deadline = time.perf_counter() + (timeout_ms / 1000.0)
        try:
            while time.perf_counter() < deadline:
                bucket = self._pending[query_id]
                if len(bucket) >= len(targets):
                    break
                remaining = deadline - time.perf_counter()
                if remaining <= 0:
                    break
                ev = self._pending_events[query_id]
                try:
                    await asyncio.wait_for(ev.wait(), timeout=remaining)
                    ev.clear()
                except asyncio.TimeoutError:
                    break
            return list(self._pending[query_id].values())
        finally:
            self._pending.pop(query_id, None)
            self._pending_events.pop(query_id, None)

    # ---- fetch from a peer ----
    async def fetch_from_peer(self, device_id: str, item_id: str,
                              timeout_ms: int = 5000) -> Optional[FetchResult]:
        peer = self._peers.get(device_id)
        if peer is None:
            return None
        fut: asyncio.Future = asyncio.get_event_loop().create_future()
        self._fetch_waiters[item_id] = fut
        try:
            await self._send(peer.ws, peer.channel, Fetch(item_id=item_id).to_dict())
            return await asyncio.wait_for(fut, timeout=timeout_ms / 1000.0)
        except asyncio.TimeoutError:
            return None
        finally:
            self._fetch_waiters.pop(item_id, None)

    async def broadcast_surface(self, surface: Surface) -> None:
        for peer in list(self._peers.values()):
            try:
                await self._send(peer.ws, peer.channel, surface.to_dict())
            except Exception:
                continue

    def peer_list(self) -> List[Dict[str, object]]:
        return [{"device_id": p.device_id, "name": p.name, "caps": p.caps}
                for p in self._peers.values()]

    async def close(self) -> None:
        for p in list(self._peers.values()):
            try:
                await p.ws.close()
            except Exception:
                pass
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
