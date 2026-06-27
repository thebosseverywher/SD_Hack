package com.flow.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Wire protocol & data contracts — Kotlin side.
 *
 * This file is the Android mirror of `shared/protocol.md` and MUST stay byte-for-byte
 * wire-compatible with the desktop engine. Every JSON field name here is taken
 * verbatim from `shared/protocol.md`; do not rename without updating the spec and
 * the desktop implementation in lock-step.
 *
 * Constants mirror `shared/config.json`. Keep [Config] in sync with that file.
 */
object Config {
    const val PROTOCOL_VERSION = 1
    const val APP_NAME = "Flow"

    const val TEXT_DIM = 384      // all-MiniLM-L6-v2 (text/semantic space)
    const val IMAGE_DIM = 512     // OpenCLIP ViT-B/32 (image/zero-shot space)
    const val WS_PORT = 8787      // federation websocket
    const val TOP_K = 8           // per-node retrieval
    const val QUERY_TIMEOUT_MS = 1500L

    val SOURCES = listOf("trove", "trail", "files", "sieve", "relay", "threads", "audio")
    val TYPES = listOf(
        "wifi", "parking", "receipt", "serial", "poster",
        "event", "doc", "activity", "note", "contact", "other"
    )

    object Models {
        const val TEXT_EMBED = "sentence-transformers/all-MiniLM-L6-v2"
        const val TEXT_EMBED_DIM = 384
        const val IMAGE_EMBED = "ViT-B-32"
        const val IMAGE_EMBED_DIM = 512
        const val LLM_PHONE = "Llama-3.2-3B-Instruct"
        const val LLM_DESKTOP = "Llama-3.1-8B-Instruct"
    }

    object Crypto {
        const val KDF = "HKDF-SHA256"
        const val AEAD = "XChaCha20-Poly1305"
        const val PSK_BYTES = 32
    }
}

/** Shared JSON codec. `encodeDefaults` keeps `v` on the wire; `ignoreUnknownKeys` is forward-compatible. */
val FlowJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

/**
 * Item — the unit stored in every node's local index (see shared/protocol.md §Item).
 *
 * `fields` is an arbitrary structured map of extracted values; we keep it as a JSON object
 * so it round-trips losslessly with the desktop engine regardless of the keys present.
 */
@Serializable
data class Item(
    val id: String,
    val device_id: String,
    val source: String,                 // one of Config.SOURCES
    val ts: Long,                       // unix seconds
    val app_context: String? = null,    // "IRCTC" | "Chrome/proposal.docx" | null
    val text: String,                   // extracted / ocr / transcript text
    val type: String,                   // one of Config.TYPES
    val fields: JsonObject = JsonObject(emptyMap()),
    val thumb_b64: String? = null,      // base64 jpeg <= 64KB | null
    val file_ref: String? = null        // origin-device path for fetch-on-open | null
)

/** Hit — a single search match returned in RESULTS (see shared/protocol.md §Hit). */
@Serializable
data class Hit(
    val item_id: String,
    val device_id: String,
    val score: Double,
    val source: String,
    val type: String,
    val text: String,                   // snippet
    val fields: JsonObject = JsonObject(emptyMap()),
    val thumb_b64: String? = null
)

/**
 * Capabilities advertised in HELLO.
 * `tops` = NPU TOPS, `has_llm` = can generate locally, `battery` = 0..100 (or -1 if plugged/unknown).
 */
@Serializable
data class Caps(
    val tops: Double = 0.0,
    val has_llm: Boolean = false,
    val battery: Int = -1
)

// ---------------------------------------------------------------------------
// Messages. Every message is { "type": <string>, "v": 1, ...payload }.
// We model the envelope discriminator as a sealed hierarchy on `type`.
// ---------------------------------------------------------------------------

/** Names of the message `type` discriminator — keep identical to shared/protocol.md. */
object MsgType {
    const val HELLO = "HELLO"
    const val QUERY = "QUERY"
    const val RESULTS = "RESULTS"
    const val FETCH = "FETCH"
    const val FETCH_RESULT = "FETCH_RESULT"
    const val SURFACE = "SURFACE"
}

@Serializable
data class Hello(
    val type: String = MsgType.HELLO,
    val v: Int = Config.PROTOCOL_VERSION,
    val device_id: String,
    // Defaulted for parity with the desktop, which models caps as a free-form dict and may
    // emit caps={} or omit it. ignoreUnknownKeys + Caps' own member defaults keep decode tolerant.
    val name: String = "",
    val caps: Caps = Caps()
)

@Serializable
data class Query(
    val type: String = MsgType.QUERY,
    val v: Int = Config.PROTOCOL_VERSION,
    val query_id: String,
    val text: String,
    val top_k: Int = Config.TOP_K
)

@Serializable
data class Results(
    val type: String = MsgType.RESULTS,
    val v: Int = Config.PROTOCOL_VERSION,
    val query_id: String,
    val device_id: String,
    val hits: List<Hit> = emptyList()
)

@Serializable
data class Fetch(
    val type: String = MsgType.FETCH,
    val v: Int = Config.PROTOCOL_VERSION,
    val item_id: String
)

@Serializable
data class FetchResult(
    val type: String = MsgType.FETCH_RESULT,
    val v: Int = Config.PROTOCOL_VERSION,
    val item_id: String,
    val mime: String = "application/octet-stream",
    val blob_b64: String = ""
)

@Serializable
data class Surface(
    val type: String = MsgType.SURFACE,
    val v: Int = Config.PROTOCOL_VERSION,
    val event: String,
    // Constrained to a JSON object to match the desktop's `dict(d.get("payload") or {})`
    // handling (shared/protocol.md shows {event, payload}); a non-object payload would
    // fail to decode on the Python side, so Android only ever emits an object here.
    val payload: JsonObject = JsonObject(emptyMap())
)

/**
 * Pairing QR payload (shared/protocol.md §Pairing):
 *   { "ip": "192.168.1.20", "port": 8787, "psk": "<base64 32-byte key>", "v": 1 }
 */
@Serializable
data class PairingInfo(
    val ip: String,
    val port: Int = Config.WS_PORT,
    val psk: String,                    // base64 of 32 raw bytes
    val v: Int = Config.PROTOCOL_VERSION
)

/**
 * Lightweight, allocation-free way to read just the `type`/`v` of an inbound frame
 * before deciding which data class to decode it into. Returns null if not JSON or no type.
 */
object Wire {
    fun peekType(jsonText: String): String? = try {
        val obj = FlowJson.parseToJsonElement(jsonText) as? JsonObject ?: return null
        (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    } catch (_: Throwable) {
        null
    }

    fun encode(item: Item): String = FlowJson.encodeToString(Item.serializer(), item)
    fun decodeItem(s: String): Item = FlowJson.decodeFromString(Item.serializer(), s)
}

/** The asker's final composed answer (shared/protocol.md §Query → answer flow, step 6). */
@Serializable
data class AnswerSource(
    val item_id: String,
    val device_id: String,
    val file_ref: String? = null
)

@Serializable
data class Answer(
    val answer: String,
    val sources: List<AnswerSource>
)
