"""Local index — items table + two vector spaces (text 384, image 512).

Primary backend: SQLite with the ``sqlite-vec`` extension (``vec0`` virtual
tables), matching the DATA_MODEL in the spec (§2.1)::

    items(id PK, device_id, source, ts, app_context, text, type, fields JSON, thumb BLOB, file_ref)
    vec_text  USING vec0(embedding FLOAT[384])
    vec_image USING vec0(embedding FLOAT[512])

If the sqlite-vec extension fails to load, the store transparently falls back
to an in-process **brute-force numpy cosine** search over vectors kept in a
plain table, so the engine still runs on any machine.

Rules enforced here (§2.2):
  * upsert / dedupe by content hash (no duplicate items),
  * never store password/OTP-flagged text.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import struct
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from . import config
from .protocol import Hit, Item

TEXT_DIM = config.TEXT_DIM
IMAGE_DIM = config.IMAGE_DIM


def _pack(vec: np.ndarray) -> bytes:
    arr = np.asarray(vec, dtype=np.float32).ravel()
    return struct.pack(f"{arr.size}f", *arr.tolist())


def _content_hash(item: Item) -> str:
    h = hashlib.sha256()
    h.update((item.device_id or "").encode())
    h.update((item.source or "").encode())
    h.update((item.text or "").encode())
    h.update(json.dumps(item.fields or {}, sort_keys=True).encode())
    return h.hexdigest()


def _is_secret(item: Item) -> bool:
    """Reject items flagged as a password/OTP capture (never persist)."""
    if item.fields and bool(item.fields.get("is_password")):
        return True
    if item.fields and bool(item.fields.get("is_secret")):
        return True
    return False


class Index:
    """SQLite-backed item + dual-vector store with numpy fallback."""

    def __init__(self, db_path: Optional[str] = None) -> None:
        self.db_path = db_path or config.DB_PATH
        Path(self.db_path).expanduser().parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(Path(self.db_path).expanduser()),
                                     check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self.vec_enabled = self._try_load_vec()
        self._init_schema()

    # ---- backend setup ----------------------------------------------------
    def _try_load_vec(self) -> bool:
        try:
            import sqlite_vec  # type: ignore

            self._conn.enable_load_extension(True)
            sqlite_vec.load(self._conn)
            self._conn.enable_load_extension(False)
            return True
        except Exception:
            return False

    def _init_schema(self) -> None:
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS items (
                    id TEXT PRIMARY KEY,
                    device_id TEXT,
                    source TEXT,
                    ts INTEGER,
                    app_context TEXT,
                    text TEXT,
                    type TEXT,
                    fields TEXT,
                    thumb_b64 TEXT,
                    file_ref TEXT,
                    chash TEXT UNIQUE
                )
                """
            )
            cur.execute("CREATE INDEX IF NOT EXISTS idx_items_source ON items(source)")
            cur.execute("CREATE INDEX IF NOT EXISTS idx_items_type ON items(type)")
            cur.execute("CREATE INDEX IF NOT EXISTS idx_items_ts ON items(ts)")

            if self.vec_enabled:
                cur.execute(
                    f"CREATE VIRTUAL TABLE IF NOT EXISTS vec_text "
                    f"USING vec0(item_id TEXT PRIMARY KEY, embedding FLOAT[{TEXT_DIM}])"
                )
                cur.execute(
                    f"CREATE VIRTUAL TABLE IF NOT EXISTS vec_image "
                    f"USING vec0(item_id TEXT PRIMARY KEY, embedding FLOAT[{IMAGE_DIM}])"
                )
            else:
                # Fallback: store raw float blobs and brute-force in numpy.
                cur.execute(
                    "CREATE TABLE IF NOT EXISTS vec_text "
                    "(item_id TEXT PRIMARY KEY, embedding BLOB)"
                )
                cur.execute(
                    "CREATE TABLE IF NOT EXISTS vec_image "
                    "(item_id TEXT PRIMARY KEY, embedding BLOB)"
                )
            self._conn.commit()

    # ---- ingestion --------------------------------------------------------
    def ingest(self, item: Item,
               text_vec: Optional[np.ndarray] = None,
               image_vec: Optional[np.ndarray] = None) -> Optional[str]:
        """Insert an item + its vectors. Returns the id, or None if skipped.

        Skips password/OTP-flagged items and exact-duplicate content hashes.
        Item + vectors are written in one transaction.
        """
        if _is_secret(item):
            return None
        chash = _content_hash(item)
        with self._lock:
            cur = self._conn.cursor()
            existing = cur.execute(
                "SELECT id FROM items WHERE chash = ?", (chash,)
            ).fetchone()
            if existing is not None:
                return None  # dedupe: no-op

            try:
                cur.execute(
                    "INSERT INTO items (id, device_id, source, ts, app_context, "
                    "text, type, fields, thumb_b64, file_ref, chash) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        item.id, item.device_id, item.source, int(item.ts),
                        item.app_context, item.text, item.type,
                        json.dumps(item.fields or {}), item.thumb_b64,
                        item.file_ref, chash,
                    ),
                )
                if text_vec is not None and np.any(text_vec):
                    self._insert_vec("vec_text", item.id, text_vec, TEXT_DIM)
                if image_vec is not None and np.any(image_vec):
                    self._insert_vec("vec_image", item.id, image_vec, IMAGE_DIM)
                self._conn.commit()
            except sqlite3.IntegrityError:
                self._conn.rollback()
                return None
        return item.id

    def ingest_batch(self, rows: List[Tuple[Item, Optional[np.ndarray], Optional[np.ndarray]]]) -> List[str]:
        out: List[str] = []
        for item, tv, iv in rows:
            rid = self.ingest(item, tv, iv)
            if rid:
                out.append(rid)
        return out

    def _insert_vec(self, table: str, item_id: str, vec: np.ndarray, dim: int) -> None:
        arr = np.asarray(vec, dtype=np.float32).ravel()
        if arr.size != dim:
            raise ValueError(f"{table}: expected dim {dim}, got {arr.size}")
        cur = self._conn.cursor()
        if self.vec_enabled:
            cur.execute(
                f"INSERT OR REPLACE INTO {table}(item_id, embedding) VALUES (?, ?)",
                (item_id, _pack(arr)),
            )
        else:
            cur.execute(
                f"INSERT OR REPLACE INTO {table}(item_id, embedding) VALUES (?, ?)",
                (item_id, arr.tobytes()),
            )

    # ---- search -----------------------------------------------------------
    def search_text(self, vec: np.ndarray, k: int = 8,
                    filters: Optional[Dict[str, Any]] = None) -> List[Hit]:
        return self._search("vec_text", vec, TEXT_DIM, k, filters)

    def search_image(self, vec: np.ndarray, k: int = 8,
                     filters: Optional[Dict[str, Any]] = None) -> List[Hit]:
        return self._search("vec_image", vec, IMAGE_DIM, k, filters)

    def _search(self, table: str, vec: np.ndarray, dim: int, k: int,
                filters: Optional[Dict[str, Any]]) -> List[Hit]:
        arr = np.asarray(vec, dtype=np.float32).ravel()
        if arr.size != dim or not np.any(arr):
            return []
        if self.vec_enabled:
            return self._search_vec(table, arr, k, filters)
        return self._search_numpy(table, arr, k, filters)

    def _filter_clause(self, filters: Optional[Dict[str, Any]]) -> Tuple[str, list]:
        if not filters:
            return "", []
        clauses, params = [], []
        if filters.get("source"):
            clauses.append("i.source = ?"); params.append(filters["source"])
        if filters.get("type"):
            clauses.append("i.type = ?"); params.append(filters["type"])
        if filters.get("since"):
            clauses.append("i.ts >= ?"); params.append(int(filters["since"]))
        if filters.get("until"):
            clauses.append("i.ts <= ?"); params.append(int(filters["until"]))
        if not clauses:
            return "", []
        return " AND " + " AND ".join(clauses), params

    def _search_vec(self, table: str, arr: np.ndarray, k: int,
                    filters: Optional[Dict[str, Any]]) -> List[Hit]:
        where, params = self._filter_clause(filters)
        # sqlite-vec applies the KNN cut (k=) *before* any constraint on a joined
        # table, so a metadata filter must NOT live inside the MATCH query or it
        # would silently drop valid matches. We run the KNN in a CTE (k only),
        # then JOIN items and apply the filter in the OUTER query. When filtering
        # we over-fetch candidates so enough survive the post-filter to fill k.
        knn_k = int(k) if not where else max(int(k) * 20, 200)
        # sqlite-vec KNN: distance is L2; vectors are L2-normalized so
        # cosine_sim = 1 - dist^2 / 2.
        sql = (
            f"WITH knn AS ("
            f"  SELECT item_id, distance FROM {table} "
            f"  WHERE embedding MATCH ? AND k = ? ORDER BY distance"
            f") "
            f"SELECT knn.item_id AS item_id, knn.distance AS distance, "
            f"i.device_id, i.source, i.type, i.text, i.fields, i.thumb_b64 "
            f"FROM knn JOIN items i ON i.id = knn.item_id "
            f"WHERE 1=1{where} "
            f"ORDER BY knn.distance LIMIT ?"
        )
        with self._lock:
            cur = self._conn.cursor()
            try:
                rows = cur.execute(
                    sql, [_pack(arr), knn_k, *params, int(k)]
                ).fetchall()
            except sqlite3.OperationalError:
                # Some sqlite-vec builds reject this form; retry numpy.
                return self._search_numpy(table, arr, k, filters)
        hits: List[Hit] = []
        for r in rows:
            dist = float(r["distance"])
            score = max(0.0, 1.0 - (dist * dist) / 2.0)
            hits.append(self._row_to_hit(r, score))
        return hits

    def _search_numpy(self, table: str, arr: np.ndarray, k: int,
                      filters: Optional[Dict[str, Any]]) -> List[Hit]:
        where, params = self._filter_clause(filters)
        sql = (
            f"SELECT v.item_id AS item_id, v.embedding AS embedding, "
            f"i.device_id, i.source, i.type, i.text, i.fields, i.thumb_b64 "
            f"FROM {table} v JOIN items i ON i.id = v.item_id WHERE 1=1{where}"
        )
        with self._lock:
            cur = self._conn.cursor()
            rows = cur.execute(sql, params).fetchall()
        if not rows:
            return []
        mats, metas = [], []
        for r in rows:
            emb = np.frombuffer(r["embedding"], dtype=np.float32)
            if emb.size != arr.size:
                continue
            mats.append(emb)
            metas.append(r)
        if not mats:
            return []
        mat = np.vstack(mats)
        sims = mat @ arr  # both L2-normalized => cosine
        order = np.argsort(-sims)[: max(1, k)]
        return [self._row_to_hit(metas[i], float(sims[i])) for i in order]

    @staticmethod
    def _row_to_hit(r: sqlite3.Row, score: float) -> Hit:
        try:
            fields = json.loads(r["fields"]) if r["fields"] else {}
        except (json.JSONDecodeError, TypeError):
            fields = {}
        return Hit(
            item_id=r["item_id"], device_id=r["device_id"], score=score,
            source=r["source"], type=r["type"], text=r["text"] or "",
            fields=fields, thumb_b64=r["thumb_b64"],
        )

    # ---- misc -------------------------------------------------------------
    def get_item(self, item_id: str) -> Optional[Item]:
        with self._lock:
            cur = self._conn.cursor()
            r = cur.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()
        if r is None:
            return None
        try:
            fields = json.loads(r["fields"]) if r["fields"] else {}
        except (json.JSONDecodeError, TypeError):
            fields = {}
        return Item(
            id=r["id"], device_id=r["device_id"], source=r["source"], ts=r["ts"],
            app_context=r["app_context"], text=r["text"] or "", type=r["type"],
            fields=fields, thumb_b64=r["thumb_b64"], file_ref=r["file_ref"],
        )

    def count(self) -> int:
        with self._lock:
            return int(self._conn.execute("SELECT COUNT(*) FROM items").fetchone()[0])

    def close(self) -> None:
        with self._lock:
            self._conn.close()
