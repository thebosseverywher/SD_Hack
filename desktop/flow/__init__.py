"""Flow — on-device, federated personal-memory search engine.

A cohesive Python package that:
  * embeds text (MiniLM, 384-d) and images (CLIP, 512-d, optional),
  * stores items + two vector spaces in SQLite (sqlite-vec, numpy fallback),
  * federates queries across a peer mesh over an encrypted WebSocket channel,
  * fuses local + peer hits (Reciprocal Rank Fusion),
  * answers with a local LLM (llama.cpp, optional) or an extractive fallback.

Runs on CPU out of the box. On-device NPU acceleration via ONNX Runtime's
QNN execution provider is the documented optimization target (see ``inference``).
"""

from .config import (
    TEXT_DIM,
    IMAGE_DIM,
    WS_PORT,
    SOURCES,
    TYPES,
    TOP_K,
    QUERY_TIMEOUT_MS,
    PROTOCOL_VERSION,
    APP_NAME,
)

__version__ = "0.1.0"

__all__ = [
    "TEXT_DIM",
    "IMAGE_DIM",
    "WS_PORT",
    "SOURCES",
    "TYPES",
    "TOP_K",
    "QUERY_TIMEOUT_MS",
    "PROTOCOL_VERSION",
    "APP_NAME",
    "__version__",
]
