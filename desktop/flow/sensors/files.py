"""Files sensor — folder indexing of documents (§3.4, §1.5).

Pick a folder; for each supported file (.pdf, .docx, .txt, .md) extract text,
chunk it (~512 tokens / 64 overlap, approximated by words), embed each chunk
(MiniLM, 384-d), and ingest as ``Item{source:files, type:doc}`` with a
``file_ref`` (absolute path) and ``fields:{page}`` for fetch-on-open.

Text extraction is CPU-only and uses pypdf / python-docx; both are core deps
but guarded so a missing one only disables that file type.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

from .. import config, embeddings
from ..index import Index
from ..protocol import Item, new_id

SUPPORTED = {".pdf", ".docx", ".txt", ".md"}

# ~512 tokens / 64 overlap, approximated in words (1 token ~= 0.75 words).
CHUNK_WORDS = 384
OVERLAP_WORDS = 48


# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------
def extract(path: Path) -> List[Tuple[str, Optional[int]]]:
    """Return list of (text, page) for a file. Page is None for non-paged docs."""
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return _extract_pdf(path)
    if suffix == ".docx":
        return _extract_docx(path)
    if suffix in (".txt", ".md"):
        try:
            return [(path.read_text(encoding="utf-8", errors="ignore"), None)]
        except OSError:
            return []
    return []


def _extract_pdf(path: Path) -> List[Tuple[str, Optional[int]]]:
    try:
        from pypdf import PdfReader  # type: ignore
    except Exception:
        return []
    out: List[Tuple[str, Optional[int]]] = []
    try:
        reader = PdfReader(str(path))
        for i, page in enumerate(reader.pages):
            txt = page.extract_text() or ""
            if txt.strip():
                out.append((txt, i + 1))
    except Exception:
        return out
    return out


def _extract_docx(path: Path) -> List[Tuple[str, Optional[int]]]:
    try:
        import docx  # type: ignore
    except Exception:
        return []
    try:
        doc = docx.Document(str(path))
        text = "\n".join(p.text for p in doc.paragraphs if p.text)
        return [(text, None)] if text.strip() else []
    except Exception:
        return []


# ---------------------------------------------------------------------------
# Chunking
# ---------------------------------------------------------------------------
def chunk_text(text: str, chunk_words: int = CHUNK_WORDS,
               overlap: int = OVERLAP_WORDS) -> List[str]:
    words = text.split()
    if not words:
        return []
    chunks, start = [], 0
    step = max(1, chunk_words - overlap)
    while start < len(words):
        chunk = " ".join(words[start:start + chunk_words])
        if chunk.strip():
            chunks.append(chunk)
        start += step
    return chunks


# ---------------------------------------------------------------------------
# Indexing
# ---------------------------------------------------------------------------
def iter_files(folder: Path) -> Iterable[Path]:
    for p in sorted(folder.rglob("*")):
        if p.is_file() and p.suffix.lower() in SUPPORTED:
            yield p


def index_folder(index: Index, folder: str, device_id: Optional[str] = None) -> Dict[str, int]:
    """Index every supported document under ``folder``. Returns counts."""
    device_id = device_id or config.DEVICE_ID or "flow-desktop"
    root = Path(folder).expanduser()
    stats = {"files": 0, "chunks": 0, "ingested": 0}
    if not root.exists():
        return stats

    for path in iter_files(root):
        pieces = extract(path)
        if not pieces:
            continue
        stats["files"] += 1
        batch: List[Tuple[Item, "object", None]] = []
        chunk_texts: List[str] = []
        chunk_meta: List[Tuple[str, Optional[int]]] = []
        for raw_text, page in pieces:
            for ch in chunk_text(raw_text):
                chunk_texts.append(ch)
                chunk_meta.append((str(path), page))
        if not chunk_texts:
            continue
        stats["chunks"] += len(chunk_texts)
        vecs = embeddings.embed_text(chunk_texts)
        for i, ch in enumerate(chunk_texts):
            fpath, page = chunk_meta[i]
            fields = {"page": page} if page is not None else {}
            item = Item(
                id=new_id(), device_id=device_id, source="files",
                ts=int(time.time()), app_context=path.name, text=ch,
                type="doc", fields=fields, file_ref=fpath,
            )
            batch.append((item, vecs[i], None))
        ingested = index.ingest_batch(batch)
        stats["ingested"] += len(ingested)
    return stats
