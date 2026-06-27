"""Result fusion & re-ranking — Reciprocal Rank Fusion (§4.5).

Merges hit lists from multiple sources (local text space, local image space,
and each peer's RESULTS) into one ranked list. RRF is rank-based so it is
robust to incomparable score scales across nodes and the two embedding spaces.

    RRF_score(item) = sum over lists L of  1 / (k + rank_L(item))

Hits are deduped by ``item_id`` (keeping the highest original score for display)
and each carries its origin ``device_id``.
"""

from __future__ import annotations

from typing import Dict, Iterable, List

from .protocol import Hit

RRF_K = 60  # standard RRF damping constant


def reciprocal_rank_fusion(hit_lists: Iterable[List[Hit]], k: int = RRF_K,
                           top_k: int | None = None) -> List[Hit]:
    """Fuse multiple ranked hit lists into one via RRF.

    Each input list is assumed already sorted best-first. Returns a single
    deduped list sorted by fused score (descending).
    """
    fused_score: Dict[str, float] = {}
    best_hit: Dict[str, Hit] = {}
    best_orig: Dict[str, float] = {}

    for hits in hit_lists:
        for rank, hit in enumerate(hits):
            key = _dedupe_key(hit)
            fused_score[key] = fused_score.get(key, 0.0) + 1.0 / (k + rank + 1)
            # Keep the representative hit with the highest original score so the
            # snippet/thumbnail shown to the user is the strongest one.
            if key not in best_orig or hit.score > best_orig[key]:
                best_orig[key] = hit.score
                best_hit[key] = hit

    ranked_keys = sorted(fused_score.keys(), key=lambda k_: fused_score[k_], reverse=True)
    out: List[Hit] = []
    for key in ranked_keys:
        hit = best_hit[key]
        # Surface the fused score on the returned hit (used for display/debug).
        fused = Hit(
            item_id=hit.item_id, device_id=hit.device_id, score=fused_score[key],
            source=hit.source, type=hit.type, text=hit.text,
            fields=dict(hit.fields or {}), thumb_b64=hit.thumb_b64,
        )
        out.append(fused)
    if top_k is not None:
        out = out[:top_k]
    return out


def _dedupe_key(hit: Hit) -> str:
    """Dedupe across the same item appearing in both spaces / multiple lists."""
    return f"{hit.device_id}:{hit.item_id}"


def fuse(hit_lists: Iterable[List[Hit]], top_k: int | None = None) -> List[Hit]:
    """Public entry point used by the brain (alias for RRF)."""
    return reciprocal_rank_fusion(hit_lists, top_k=top_k)
