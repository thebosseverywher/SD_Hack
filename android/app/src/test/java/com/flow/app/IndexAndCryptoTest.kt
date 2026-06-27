package com.flow.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexAndCryptoTest {

    private fun item(id: String, text: String) = Item(
        id = id, device_id = "dev", source = "trove", ts = 1, app_context = null,
        text = text, type = "other"
    )

    @Test
    fun known_item_is_top1_for_its_own_text_vector() {
        val index = InMemoryIndex()
        val vecs = HashMap<String, FloatArray>()
        repeat(50) { i ->
            val t = "document number $i about topic $i"
            val v = Inference.deterministicVector(t, Config.TEXT_DIM)
            vecs["id$i"] = v
            index.ingest(item("id$i", t), textVec = v)
        }
        val target = vecs["id7"]!!
        val hits = index.searchText(target, k = 5)
        assertEquals("id7", hits.first().item_id)
    }

    @Test
    fun ingest_dedupes_by_id() {
        val index = InMemoryIndex()
        val v = FloatArray(Config.TEXT_DIM)
        index.ingest(item("dup", "a"), textVec = v)
        index.ingest(item("dup", "a"), textVec = v)
        assertEquals(1, index.count())
    }

    @Test
    fun blank_item_with_no_vectors_is_rejected() {
        val index = InMemoryIndex()
        assertNull(index.ingest(item("blank", "   ")))
    }

    @Test
    fun hkdf_is_deterministic_and_sized() {
        val psk = ByteArray(32) { it.toByte() }
        val k1 = AeadChannel.hkdfSha256(psk, "salt".toByteArray(), "info".toByteArray(), 32)
        val k2 = AeadChannel.hkdfSha256(psk, "salt".toByteArray(), "info".toByteArray(), 32)
        assertEquals(32, k1.size)
        assertTrue(k1.contentEquals(k2))
    }

    @Test
    fun fusion_dedupes_and_ranks() {
        val a = Hit("x", "devA", 0.9, "trove", "wifi", "snippetA")
        val b = Hit("y", "devB", 0.8, "files", "doc", "snippetB")
        val fused = Fusion.fuse(listOf(listOf(a, b), listOf(a)))
        // x appears in both lists -> should outrank y.
        assertEquals("x", fused.first().item_id)
        assertEquals(2, fused.size)
        assertNotNull(fused.first())
    }
}
