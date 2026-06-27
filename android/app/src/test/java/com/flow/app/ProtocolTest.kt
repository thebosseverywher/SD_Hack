package com.flow.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-compatibility tests: every field name below is asserted against the exact
 * spelling in shared/protocol.md. If the desktop engine and this app disagree, one
 * of these assertions should fail.
 */
class ProtocolTest {

    @Test
    fun item_roundtrips_with_spec_field_names() {
        val item = Item(
            id = "uuid-1",
            device_id = "dev-A",
            source = "trove",
            ts = 1719500000,
            app_context = "Chrome/proposal.docx",
            text = "wifi pass hunter2",
            type = "wifi",
            fields = JsonObject(mapOf("ssid" to JsonPrimitive("home"))),
            thumb_b64 = "Zm9v",
            file_ref = "/sdcard/x.jpg"
        )
        val json = Wire.encode(item)
        // Required keys present with exact names.
        for (k in listOf("id", "device_id", "source", "ts", "app_context", "text",
            "type", "fields", "thumb_b64", "file_ref")) {
            assertTrue("missing key $k in $json", json.contains("\"$k\""))
        }
        assertEquals(item, Wire.decodeItem(json))
    }

    @Test
    fun query_results_envelope_has_type_and_version() {
        val q = Query(query_id = "q1", text = "where did I park", top_k = 8)
        val s = FlowJson.encodeToString(Query.serializer(), q)
        assertTrue(s.contains("\"type\":\"QUERY\""))
        assertTrue(s.contains("\"v\":1"))
        assertTrue(s.contains("\"query_id\":\"q1\""))
        assertTrue(s.contains("\"top_k\":8"))

        val hit = Hit("i1", "dev-B", 0.83, "trove", "serial", "snip")
        val r = Results(query_id = "q1", device_id = "dev-B", hits = listOf(hit))
        val rs = FlowJson.encodeToString(Results.serializer(), r)
        assertTrue(rs.contains("\"type\":\"RESULTS\""))
        assertTrue(rs.contains("\"item_id\":\"i1\""))
        assertTrue(rs.contains("\"score\":0.83"))
    }

    @Test
    fun pairing_payload_matches_spec() {
        val info = PairingInfo(ip = "192.168.1.20", port = 8787, psk = "QUJD", v = 1)
        val s = FlowJson.encodeToString(PairingInfo.serializer(), info)
        for (k in listOf("ip", "port", "psk", "v")) assertTrue(s.contains("\"$k\""))
    }

    @Test
    fun config_constants_match_shared_config() {
        assertEquals(384, Config.TEXT_DIM)
        assertEquals(512, Config.IMAGE_DIM)
        assertEquals(8787, Config.WS_PORT)
        assertEquals(8, Config.TOP_K)
        assertEquals(32, Config.Crypto.PSK_BYTES)
    }
}
