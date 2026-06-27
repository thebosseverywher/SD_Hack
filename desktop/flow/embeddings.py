"""Embeddings — text (384-d, MiniLM) and image (512-d, CLIP, optional).

Two intentionally separate spaces (see protocol.md / spec §1.1, §1.3):
  * ``embed_text``  -> 384-d, sentence-transformers all-MiniLM-L6-v2 (CPU default).
  * ``embed_image`` -> 512-d, open_clip ViT-B/32 if installed, else a disabled stub.

All vectors are L2-normalized float32 so cosine == dot product. If a backend is
unavailable the functions return zero vectors of the correct dimension and mark
themselves disabled, so the rest of the engine keeps working.
"""

from __future__ import annotations

import threading
from typing import List, Optional, Sequence

import numpy as np

from . import config
from .telemetry import TELEMETRY

TEXT_DIM = config.TEXT_DIM
IMAGE_DIM = config.IMAGE_DIM


def _l2_normalize(mat: np.ndarray) -> np.ndarray:
    mat = np.asarray(mat, dtype=np.float32)
    if mat.ndim == 1:
        mat = mat[None, :]
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return (mat / norms).astype(np.float32)


# ---------------------------------------------------------------------------
# Text embeddings
# ---------------------------------------------------------------------------
class _TextEmbedder:
    def __init__(self) -> None:
        self._model = None
        self._lock = threading.Lock()
        self._tried = False
        self.enabled = False
        self.dim = TEXT_DIM

    def _ensure(self) -> None:
        if self._tried:
            return
        with self._lock:
            if self._tried:
                return
            self._tried = True
            try:
                from sentence_transformers import SentenceTransformer  # type: ignore

                # CPU by default. NPU note: an ONNX export of MiniLM can be run
                # through flow.inference.OnnxModel on the QNN EP — see README.
                self._model = SentenceTransformer(config.TEXT_EMBED_MODEL, device="cpu")
                self.enabled = True
            except Exception:
                self._model = None
                self.enabled = False

    def embed(self, texts: Sequence[str]) -> np.ndarray:
        if not texts:
            return np.zeros((0, self.dim), dtype=np.float32)
        self._ensure()
        if not self.enabled:
            # Disabled stub: deterministic zero vectors keep shapes valid.
            return np.zeros((len(texts), self.dim), dtype=np.float32)
        with TELEMETRY.stage("embed_text", ep="CPU"):
            vecs = self._model.encode(
                list(texts), batch_size=32, convert_to_numpy=True,
                normalize_embeddings=False, show_progress_bar=False,
            )
        return _l2_normalize(np.asarray(vecs, dtype=np.float32))


# ---------------------------------------------------------------------------
# Image embeddings (optional)
# ---------------------------------------------------------------------------
class _ImageEmbedder:
    def __init__(self) -> None:
        self._model = None
        self._preprocess = None
        self._tokenizer = None
        self._lock = threading.Lock()
        self._tried = False
        self.enabled = False
        self.dim = IMAGE_DIM

    def _ensure(self) -> None:
        if self._tried:
            return
        with self._lock:
            if self._tried:
                return
            self._tried = True
            try:
                import open_clip  # type: ignore
                import torch  # noqa: F401  type: ignore

                model, _, preprocess = open_clip.create_model_and_transforms(
                    config.IMAGE_EMBED_MODEL, pretrained="openai"
                )
                model.eval()
                self._model = model
                self._preprocess = preprocess
                self._tokenizer = open_clip.get_tokenizer(config.IMAGE_EMBED_MODEL)
                self.enabled = True
            except Exception:
                self.enabled = False

    def embed_image(self, image) -> np.ndarray:
        """Embed a PIL.Image into a 512-d vector (zeros if CLIP unavailable)."""
        self._ensure()
        if not self.enabled:
            return np.zeros((self.dim,), dtype=np.float32)
        import torch  # type: ignore

        with TELEMETRY.stage("embed_image", ep="CPU"):
            tensor = self._preprocess(image.convert("RGB")).unsqueeze(0)
            with torch.no_grad():
                feats = self._model.encode_image(tensor)
            vec = feats.cpu().numpy()[0]
        return _l2_normalize(vec)[0]

    def embed_text(self, texts: Sequence[str]) -> np.ndarray:
        """CLIP *text* encoder -> 512-d (for zero-shot / text->image search)."""
        self._ensure()
        if not self.enabled:
            return np.zeros((len(texts), self.dim), dtype=np.float32)
        import torch  # type: ignore

        with TELEMETRY.stage("embed_clip_text", ep="CPU"):
            tokens = self._tokenizer(list(texts))
            with torch.no_grad():
                feats = self._model.encode_text(tokens)
            vecs = feats.cpu().numpy()
        return _l2_normalize(np.asarray(vecs, dtype=np.float32))

    def classify_type(self, image, prompts: dict) -> tuple:
        """Zero-shot type via a {type: prompt} dict. Returns (type, score).

        Falls back to ("other", 0.0) when CLIP is unavailable.
        """
        self._ensure()
        if not self.enabled or not prompts:
            return ("other", 0.0)
        img_vec = self.embed_image(image)
        types = list(prompts.keys())
        txt_vecs = self.embed_text([prompts[t] for t in types])
        sims = txt_vecs @ img_vec
        idx = int(np.argmax(sims))
        return (types[idx], float(sims[idx]))


# Singletons
_TEXT = _TextEmbedder()
_IMAGE = _ImageEmbedder()


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------
def embed_text(texts: List[str]) -> np.ndarray:
    """Embed a list of strings -> float32 [N, 384], L2-normalized."""
    return _TEXT.embed(texts)


def embed_image(image) -> np.ndarray:
    """Embed a PIL image -> float32 [512], L2-normalized (zeros if disabled)."""
    return _IMAGE.embed_image(image)


def embed_clip_text(texts: List[str]) -> np.ndarray:
    """CLIP text encoder -> float32 [N, 512] (zeros if disabled)."""
    return _IMAGE.embed_text(texts)


def classify_image_type(image, prompts: Optional[dict] = None) -> tuple:
    """Zero-shot CLIP type classification -> (type, score)."""
    if prompts is None:
        prompts = DEFAULT_TYPE_PROMPTS
    return _IMAGE.classify_type(image, prompts)


def text_enabled() -> bool:
    _TEXT._ensure()
    return _TEXT.enabled


def image_enabled() -> bool:
    _IMAGE._ensure()
    return _IMAGE.enabled


# Default zero-shot prompts for utility-photo typing (spec §1.3).
DEFAULT_TYPE_PROMPTS = {
    "wifi": "a photo of a wifi password sticker",
    "parking": "a photo of a parking spot or parking level sign",
    "receipt": "a photo of a receipt or bill",
    "serial": "a photo of a serial number label",
    "poster": "a photo of an event poster or flyer",
    "other": "a photo",
}
