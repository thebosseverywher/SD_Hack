"""Configuration loader.

Loads ``shared/config.json`` (the single source of truth shared with the
Android app) and exposes its constants as module-level names. If the shared
file cannot be found (e.g. the package was installed standalone), a set of
baked-in defaults that match the committed contract is used so the engine
still runs.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, List

# ---------------------------------------------------------------------------
# Locate shared/config.json. We walk up from this file: desktop/flow/ -> repo.
# ---------------------------------------------------------------------------

_DEFAULTS: Dict[str, Any] = {
    "protocol_version": 1,
    "app_name": "Flow",
    "text_dim": 384,
    "image_dim": 512,
    "ws_port": 8787,
    "top_k": 8,
    "query_timeout_ms": 1500,
    "sources": ["trove", "trail", "files", "sieve", "relay", "threads", "audio"],
    "types": [
        "wifi", "parking", "receipt", "serial", "poster",
        "event", "doc", "activity", "note", "contact", "other",
    ],
    "models": {
        "text_embed": "sentence-transformers/all-MiniLM-L6-v2",
        "text_embed_dim": 384,
        "image_embed": "ViT-B-32",
        "image_embed_dim": 512,
        "llm_phone": "Llama-3.2-3B-Instruct",
        "llm_desktop": "Llama-3.1-8B-Instruct",
    },
    "crypto": {
        "kdf": "HKDF-SHA256",
        "aead": "XChaCha20-Poly1305",
        "psk_bytes": 32,
    },
}


def _find_config_path() -> Path | None:
    """Return the path to shared/config.json if discoverable."""
    # 1) Explicit override.
    env = os.environ.get("FLOW_CONFIG")
    if env and Path(env).is_file():
        return Path(env)

    # 2) Walk up from this file looking for shared/config.json.
    here = Path(__file__).resolve()
    for parent in [here.parent, *here.parents]:
        candidate = parent / "shared" / "config.json"
        if candidate.is_file():
            return candidate
    return None


def load_config() -> Dict[str, Any]:
    """Load and return the merged config dict (shared file over defaults)."""
    cfg = json.loads(json.dumps(_DEFAULTS))  # deep copy
    path = _find_config_path()
    if path is not None:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                loaded = json.load(fh)
            cfg.update(loaded)
        except (OSError, json.JSONDecodeError):
            # Corrupt or unreadable — fall back to defaults silently.
            pass
    return cfg


CONFIG: Dict[str, Any] = load_config()

# ---------------------------------------------------------------------------
# Public constants
# ---------------------------------------------------------------------------
PROTOCOL_VERSION: int = int(CONFIG["protocol_version"])
APP_NAME: str = str(CONFIG["app_name"])
TEXT_DIM: int = int(CONFIG["text_dim"])
IMAGE_DIM: int = int(CONFIG["image_dim"])
WS_PORT: int = int(CONFIG["ws_port"])
TOP_K: int = int(CONFIG["top_k"])
QUERY_TIMEOUT_MS: int = int(CONFIG["query_timeout_ms"])
SOURCES: List[str] = list(CONFIG["sources"])
TYPES: List[str] = list(CONFIG["types"])

# Model names
MODELS: Dict[str, Any] = dict(CONFIG["models"])
TEXT_EMBED_MODEL: str = str(MODELS["text_embed"])
IMAGE_EMBED_MODEL: str = str(MODELS["image_embed"])
LLM_DESKTOP_MODEL: str = str(MODELS["llm_desktop"])
LLM_PHONE_MODEL: str = str(MODELS["llm_phone"])

# Crypto
CRYPTO: Dict[str, Any] = dict(CONFIG["crypto"])
PSK_BYTES: int = int(CRYPTO["psk_bytes"])

# ---------------------------------------------------------------------------
# Runtime-tunable knobs (env-overridable). These do not affect wire-compat.
# ---------------------------------------------------------------------------
DB_PATH: str = os.environ.get(
    "FLOW_DB", str(Path.home() / ".flow" / "flow.db")
)
DEVICE_ID: str = os.environ.get("FLOW_DEVICE_ID", "") or ""  # filled by app if empty
DEVICE_NAME: str = os.environ.get("FLOW_DEVICE_NAME", "flow-desktop")
LLM_MODEL_PATH: str = os.environ.get("FLOW_LLM_MODEL", "")  # GGUF path; empty => extractive

# QNN / NPU
# Path to the QNN HTP backend shared library, if installed. Used by inference.py
# to enable the QNN execution provider. Empty => CPU only.
QNN_BACKEND_PATH: str = os.environ.get("FLOW_QNN_BACKEND", "")


def summary() -> Dict[str, Any]:
    """A small dict describing the active config (for /healthz and logs)."""
    return {
        "app_name": APP_NAME,
        "protocol_version": PROTOCOL_VERSION,
        "text_dim": TEXT_DIM,
        "image_dim": IMAGE_DIM,
        "ws_port": WS_PORT,
        "top_k": TOP_K,
        "query_timeout_ms": QUERY_TIMEOUT_MS,
        "config_path": str(_find_config_path() or "<defaults>"),
    }
