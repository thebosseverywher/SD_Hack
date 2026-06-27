"""Fusion tests — RRF ordering, dedupe, device tagging."""

from flow.fusion import reciprocal_rank_fusion, fuse
from flow.protocol import Hit


def h(item_id, device="d1", score=0.5, source="files", type="doc"):
    return Hit(item_id=item_id, device_id=device, score=score,
               source=source, type=type, text=f"text {item_id}")


def test_rrf_rewards_consensus():
    # Item "A" ranks high in both lists -> should win even if not #1 anywhere.
    list1 = [h("X"), h("A"), h("B")]
    list2 = [h("Y"), h("A"), h("C")]
    fused = reciprocal_rank_fusion([list1, list2])
    assert fused[0].item_id == "A"


def test_rrf_dedupes_same_item():
    list1 = [h("A"), h("B")]
    list2 = [h("A"), h("C")]  # A appears in both
    fused = reciprocal_rank_fusion([list1, list2])
    ids = [x.item_id for x in fused]
    assert ids.count("A") == 1


def test_rrf_keeps_device_tag_and_best_snippet():
    list1 = [h("A", device="laptop", score=0.4)]
    list2 = [h("A", device="phone", score=0.9)]
    fused = reciprocal_rank_fusion([list1, list2])
    # representative hit is the higher-original-score one (phone)
    assert fused[0].device_id == "phone"


def test_top_k_truncation():
    lists = [[h(str(i)) for i in range(10)]]
    fused = fuse(lists, top_k=3)
    assert len(fused) == 3
