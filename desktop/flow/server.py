"""FastAPI app — web UI, /ask, /pair (QR), federation websocket, telemetry.

Endpoints:
  * GET  /                 -> serves the web UI (ui/index.html + assets).
  * POST /ask              -> brain.ask(text) -> {answer, sources, hits, telemetry}.
  * GET  /pair             -> pairing JSON; GET /pair.png -> QR PNG.
  * GET  /telemetry        -> telemetry snapshot for the panel.
  * POST /telemetry/ep     -> set active EP for the NPU/CPU toggle.
  * POST /index/files|trove-> index a folder via the sensors.
  * GET  /peers            -> connected peers.
  * GET  /healthz          -> config + capability summary.
  * WS   /flow             -> federation peer endpoint (encrypted frames).

The WebSocket endpoint bridges FastAPI's websocket to the federation handlers.
"""

from __future__ import annotations

import asyncio
import json
import socket
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import (
    FileResponse, HTMLResponse, JSONResponse, Response,
)
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import config, embeddings
from .brain import Brain
from .crypto import SecureChannel, ReplayError
from .federation import Federation, make_qr_png
from .index import Index
from .inference import EP_CPU, EP_QNN, qnn_available
from .protocol import FetchResult, decode_message
from .telemetry import TELEMETRY

UI_DIR = Path(__file__).resolve().parent / "ui"


# ---------------------------------------------------------------------------
# App state container
# ---------------------------------------------------------------------------
class AppState:
    def __init__(self, db_path: Optional[str] = None,
                 device_id: Optional[str] = None,
                 device_name: Optional[str] = None,
                 port: int = config.WS_PORT) -> None:
        self.device_id = device_id or config.DEVICE_ID or f"flow-desktop-{socket.gethostname()}"
        self.device_name = device_name or config.DEVICE_NAME
        self.index = Index(db_path)
        self.federation = Federation(
            device_id=self.device_id, name=self.device_name, port=port,
            search_fn=self._search_fn, fetch_fn=self._fetch_fn,
        )
        self.brain = Brain(self.index, federation=self.federation,
                           device_id=self.device_id)

    def _search_fn(self, text: str, top_k: int):
        return self.brain.local_search(text, top_k)

    def _fetch_fn(self, item_id: str) -> Optional[FetchResult]:
        import base64
        item = self.index.get_item(item_id)
        if item is None or not item.file_ref:
            return None
        p = Path(item.file_ref)
        if not p.is_file():
            return FetchResult(item_id=item_id, mime="application/octet-stream", blob_b64="")
        data = p.read_bytes()
        mime = "application/pdf" if p.suffix.lower() == ".pdf" else "application/octet-stream"
        if p.suffix.lower() in (".jpg", ".jpeg"):
            mime = "image/jpeg"
        elif p.suffix.lower() == ".png":
            mime = "image/png"
        return FetchResult(item_id=item_id, mime=mime,
                           blob_b64=base64.b64encode(data).decode("ascii"))


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------
class AskRequest(BaseModel):
    text: str
    top_k: int = config.TOP_K


class EpRequest(BaseModel):
    ep: str  # "CPU" | "QNN"


class IndexRequest(BaseModel):
    path: str
    use_ocr: bool = True


# ---------------------------------------------------------------------------
# App factory
# ---------------------------------------------------------------------------
def create_app(state: Optional[AppState] = None) -> FastAPI:
    state = state or AppState()
    app = FastAPI(title=f"{config.APP_NAME} desktop engine")
    app.state.flow = state

    if UI_DIR.is_dir():
        app.mount("/ui", StaticFiles(directory=str(UI_DIR)), name="ui")

    @app.on_event("startup")
    async def _startup() -> None:
        # Start the federation WS server alongside the HTTP server when possible.
        try:
            await state.federation.start_server()
        except Exception:
            pass  # websockets missing or port busy -> local-only mode

    @app.get("/", response_class=HTMLResponse)
    async def root() -> HTMLResponse:
        idx = UI_DIR / "index.html"
        if idx.is_file():
            return HTMLResponse(idx.read_text(encoding="utf-8"))
        return HTMLResponse("<h1>Flow</h1><p>UI assets not found.</p>")

    @app.get("/healthz")
    async def healthz() -> JSONResponse:
        return JSONResponse({
            "config": config.summary(),
            "device_id": state.device_id,
            "items": state.index.count(),
            "vec_backend": "sqlite-vec" if state.index.vec_enabled else "numpy-fallback",
            "text_embed": embeddings.text_enabled(),
            "image_embed": embeddings.image_enabled(),
            "qnn_available": qnn_available(),
            "active_ep": TELEMETRY.active_ep,
        })

    @app.post("/ask")
    async def ask(req: AskRequest) -> JSONResponse:
        result = await state.brain.ask_async(req.text, top_k=req.top_k)
        return JSONResponse(result)

    @app.get("/pair")
    async def pair() -> JSONResponse:
        return JSONResponse(state.federation.pairing_payload())

    @app.get("/pair.png")
    async def pair_png() -> Response:
        png = make_qr_png(state.federation.pairing_payload())
        return Response(content=png, media_type="image/png")

    @app.get("/telemetry")
    async def telemetry() -> JSONResponse:
        return JSONResponse(TELEMETRY.snapshot())

    @app.post("/telemetry/ep")
    async def set_ep(req: EpRequest) -> JSONResponse:
        ep = EP_QNN if req.ep.upper() == EP_QNN else EP_CPU
        # If QNN requested but unavailable, report honest fallback to CPU.
        if ep == EP_QNN and not qnn_available():
            TELEMETRY.active_ep = EP_CPU
            return JSONResponse({"active_ep": EP_CPU, "requested": EP_QNN,
                                 "note": "QNN EP unavailable on this machine; using CPU."})
        TELEMETRY.active_ep = ep
        return JSONResponse({"active_ep": ep})

    @app.get("/peers")
    async def peers() -> JSONResponse:
        return JSONResponse({"peers": state.federation.peer_list()})

    @app.post("/index/files")
    async def index_files(req: IndexRequest) -> JSONResponse:
        from .sensors import files as files_sensor
        stats = files_sensor.index_folder(state.index, req.path, state.device_id)
        return JSONResponse(stats)

    @app.post("/index/trove")
    async def index_trove(req: IndexRequest) -> JSONResponse:
        from .sensors import trove as trove_sensor
        stats = trove_sensor.index_folder(state.index, req.path, state.device_id,
                                          use_ocr=req.use_ocr)
        return JSONResponse(stats)

    @app.websocket("/flow")
    async def ws_endpoint(ws: WebSocket) -> None:
        await ws.accept()
        channel = SecureChannel(state.federation.psk)
        peer_id = None
        try:
            while True:
                raw = await ws.receive_text()
                try:
                    pt = channel.open_b64(raw)
                    msg = decode_message(json.loads(pt.decode("utf-8")))
                except ReplayError:
                    TELEMETRY.record("replay_rejected", "n/a", 0.0)
                    continue
                except Exception:
                    continue
                # Reuse federation dispatch; it sends replies via this ws.
                peer_id = await state.federation._dispatch(_WSAdapter(ws), channel, msg, peer_id)
        except WebSocketDisconnect:
            pass
        finally:
            if peer_id and peer_id in state.federation._peers:
                state.federation._peers.pop(peer_id, None)

    return app


class _WSAdapter:
    """Adapt FastAPI WebSocket.send to the .send(str) used by federation."""

    def __init__(self, ws: WebSocket) -> None:
        self._ws = ws

    async def send(self, data: str) -> None:
        await self._ws.send_text(data)

    async def close(self) -> None:
        await self._ws.close()


def serve(host: str = "127.0.0.1", port_http: int = 8000,
          db_path: Optional[str] = None, device_id: Optional[str] = None) -> None:
    """Run the HTTP server (blocking). Federation WS runs on config.WS_PORT."""
    import uvicorn

    state = AppState(db_path=db_path, device_id=device_id)
    app = create_app(state)
    uvicorn.run(app, host=host, port=port_http, log_level="info")
