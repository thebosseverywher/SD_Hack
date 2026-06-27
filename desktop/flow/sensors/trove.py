"""Trove sensor — index image files into typed, searchable items (§3.1).

For each image in a folder:
  * optional OCR (easyocr if installed) -> text,
  * CLIP zero-shot type + 512-d image embedding (open_clip if installed),
  * 384-d text embedding of the OCR text,
  * regex field extraction (wifi ssid/pass, amounts, serials, dates),
  * a small JPEG thumbnail (<=64KB) for results,
  * ingest as ``Item{source:trove, type, fields, thumb_b64, file_ref}`` with
    BOTH a text vector and an image vector.

Degrades gracefully: no OCR -> empty text; no CLIP -> image space skipped and
type falls back to regex heuristics or "other".
"""

from __future__ import annotations

import base64
import io
import re
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

from .. import config, embeddings
from ..index import Index
from ..protocol import Item, new_id

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".heic"}

# ---------------------------------------------------------------------------
# Field extraction (regex)
# ---------------------------------------------------------------------------
_WIFI_SSID = re.compile(r"(?:ssid|network|wifi)\s*[:\-]?\s*([^\n\r,;]{2,40})", re.I)
_WIFI_PASS = re.compile(r"(?:password|pass|key|pwd)\s*[:\-]?\s*([^\n\r,;\s]{4,40})", re.I)
_AMOUNT = re.compile(r"(?:[$₹€£]\s?\d[\d,]*(?:\.\d{1,2})?)|(?:\b\d[\d,]*\.\d{2}\b)")
_SERIAL = re.compile(r"(?:serial|s/n|sn)\s*[:\-]?\s*([A-Z0-9\-]{5,})", re.I)
_DATE = re.compile(r"\b(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}|\d{4}-\d{2}-\d{2})\b")


def extract_fields(text: str) -> Tuple[Dict[str, str], Optional[str]]:
    """Return (fields, inferred_type) from OCR text using regex heuristics."""
    fields: Dict[str, str] = {}
    inferred: Optional[str] = None
    if not text:
        return fields, inferred

    if (m := _WIFI_SSID.search(text)):
        fields["ssid"] = m.group(1).strip()
        inferred = "wifi"
    if (m := _WIFI_PASS.search(text)):
        # NOTE: a wifi sticker password is the *point* of the wifi item; it is
        # NOT a user account password/OTP, so it is fine to store. We never set
        # is_password here (that flag is reserved for genuine secret capture).
        fields["pass"] = m.group(1).strip()
        inferred = "wifi"
    if (m := _SERIAL.search(text)):
        fields["serial"] = m.group(1).strip()
        inferred = inferred or "serial"
    if (m := _AMOUNT.search(text)):
        fields["amount"] = m.group(0).strip()
        inferred = inferred or "receipt"
    if (m := _DATE.search(text)):
        fields["when"] = m.group(1).strip()
    return fields, inferred


# ---------------------------------------------------------------------------
# OCR (optional)
# ---------------------------------------------------------------------------
class _OCR:
    def __init__(self) -> None:
        self._reader = None
        self._tried = False
        self.enabled = False

    def _ensure(self) -> None:
        if self._tried:
            return
        self._tried = True
        try:
            import easyocr  # type: ignore
            self._reader = easyocr.Reader(["en"], gpu=False, verbose=False)
            self.enabled = True
        except Exception:
            self.enabled = False

    def read(self, image_path: str) -> str:
        self._ensure()
        if not self.enabled:
            return ""
        try:
            results = self._reader.readtext(image_path, detail=0)
            return "\n".join(results)
        except Exception:
            return ""


_OCR_SINGLETON = _OCR()


# ---------------------------------------------------------------------------
# Thumbnails
# ---------------------------------------------------------------------------
def make_thumb_b64(image, max_px: int = 256, max_bytes: int = 64 * 1024) -> Optional[str]:
    try:
        img = image.convert("RGB")
        img.thumbnail((max_px, max_px))
        for quality in (75, 60, 45, 30):
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=quality)
            data = buf.getvalue()
            if len(data) <= max_bytes:
                return base64.b64encode(data).decode("ascii")
        return base64.b64encode(data).decode("ascii")
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Indexing
# ---------------------------------------------------------------------------
def index_folder(index: Index, folder: str, device_id: Optional[str] = None,
                 use_ocr: bool = True) -> Dict[str, int]:
    """Index every image under ``folder``. Returns counts."""
    from PIL import Image  # pillow is a core dep

    device_id = device_id or config.DEVICE_ID or "flow-desktop"
    root = Path(folder).expanduser()
    stats = {"images": 0, "ingested": 0, "ocr": 0, "clip": 0}
    if not root.exists():
        return stats

    for path in sorted(root.rglob("*")):
        if not (path.is_file() and path.suffix.lower() in IMAGE_EXTS):
            continue
        stats["images"] += 1
        try:
            img = Image.open(path)
            img.load()
        except Exception:
            continue

        ocr_text = _OCR_SINGLETON.read(str(path)) if use_ocr else ""
        if ocr_text:
            stats["ocr"] += 1
        fields, inferred = extract_fields(ocr_text)

        # CLIP type + image vector (optional).
        image_vec = None
        itype = inferred or "other"
        if embeddings.image_enabled():
            ctype, score = embeddings.classify_image_type(img)
            if not inferred and score > 0.0:
                itype = ctype
            image_vec = embeddings.embed_image(img)
            stats["clip"] += 1

        # Text embedding of OCR text (so photos are searchable in text space).
        search_text = ocr_text or f"{itype} photo {path.stem}"
        tvec = embeddings.embed_text([search_text])
        text_vec = tvec[0] if tvec.shape[0] else None

        item = Item(
            id=new_id(), device_id=device_id, source="trove", ts=int(time.time()),
            app_context=path.name, text=ocr_text or search_text, type=itype,
            fields=fields, thumb_b64=make_thumb_b64(img), file_ref=str(path),
        )
        rid = index.ingest(item, text_vec=text_vec, image_vec=image_vec)
        if rid:
            stats["ingested"] += 1
    return stats
