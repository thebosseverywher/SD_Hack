package com.flow.app

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Federation peer (spec §4) — Android side.
 *
 * Each node is a symmetric peer. On Android we implement the **client** half with
 * OkHttp's WebSocket: after scanning the laptop's QR we dial ws://ip:port and run
 * the HELLO/QUERY/RESULTS/FETCH/SURFACE protocol from shared/protocol.md.
 *
 * NOTE on the "server-ish" half: OkHttp has no embedded WS server. For the hackathon
 * the laptop is the pairing initiator and accepts the connection, so the phone only
 * needs the client. If you want the phone to also accept inbound peers (true mesh),
 * add Ktor's embedded server behind the same [PeerLink] interface — see the TODO in
 * [FederationManager]. The desktop engine listens on Config.WS_PORT.
 *
 * Every frame is AEAD-encrypted with a key derived from the pairing PSK (spec §0.5).
 * shared/config.json specifies XChaCha20-Poly1305 + HKDF-SHA256. The JDK/Android
 * crypto providers do not ship XChaCha20-Poly1305, so this skeleton uses a CLEARLY
 * DOCUMENTED interop-friendly stub ([AeadChannel]) built on AES-256-GCM + HKDF-SHA256.
 *
 *   >>> Before interop with the desktop engine, replace AeadChannel with a real
 *       XChaCha20-Poly1305 implementation (Tink's `XChaCha20Poly1305` /
 *       libsodium via JNI) so both ends agree on the AEAD. The wrap/unwrap framing
 *       (nonce-prefixed, base64) is kept identical so only the cipher swaps out.
 */

private const val TAG = "Flow/Federation"

/** Abstraction over a single peer connection so the transport can be swapped. */
interface PeerLink {
    val deviceId: String
    fun send(plaintextJson: String)
    fun close()
}

/**
 * AEAD channel keyed by the pairing PSK.
 *
 * Wire framing (must match the desktop engine): base64( nonce(12B) || ciphertext||tag ).
 * Key derivation: HKDF-SHA256(psk, salt="flow-fed-v1", info="aead-key") -> 32 bytes.
 *
 * Cipher: **AES-256-GCM stub** standing in for the spec's XChaCha20-Poly1305 — see file
 * header. The nonce is a 12-byte monotonic counter prefixed with 4 random bytes; a
 * replay window rejects out-of-order/duplicate counters (spec §0.5).
 */
class AeadChannel(psk: ByteArray) {
    private val key: ByteArray = hkdfSha256(psk, "flow-fed-v1".toByteArray(), "aead-key".toByteArray(), 32)
    private val keySpec = SecretKeySpec(key, "AES")

    @Volatile private var sendCounter: Long = 0
    private val randPrefix = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }

    // Replay protection: highest counter seen + a small sliding window.
    @Volatile private var highestSeen: Long = -1
    private val window = sortedSetOf<Long>()

    fun seal(plaintext: String): String {
        val nonce = ByteArray(12)
        System.arraycopy(randPrefix, 0, nonce, 0, 4)
        val ctr = synchronized(this) { ++sendCounter }
        for (i in 0 until 8) nonce[4 + i] = (ctr ushr (8 * (7 - i))).toByte()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val frame = nonce + ct
        return Base64.encodeToString(frame, Base64.NO_WRAP)
    }

    /** @return plaintext JSON, or null if the frame is malformed, fails auth, or is a replay. */
    fun open(frameB64: String): String? {
        return try {
            val frame = Base64.decode(frameB64, Base64.NO_WRAP)
            if (frame.size < 12 + 16) return null
            val nonce = frame.copyOfRange(0, 12)
            val ct = frame.copyOfRange(12, frame.size)

            // Replay check on the 8-byte counter portion of the nonce.
            var ctr = 0L
            for (i in 0 until 8) ctr = (ctr shl 8) or (nonce[4 + i].toLong() and 0xFF)
            if (!acceptCounter(ctr)) {
                Log.w(TAG, "rejected replayed/old frame ctr=$ctr")
                return null
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "open() failed: ${t.message}")
            null
        }
    }

    @Synchronized
    private fun acceptCounter(ctr: Long): Boolean {
        if (ctr <= highestSeen - REPLAY_WINDOW) return false
        if (window.contains(ctr)) return false
        window.add(ctr)
        if (ctr > highestSeen) highestSeen = ctr
        while (window.isNotEmpty() && window.first() < highestSeen - REPLAY_WINDOW) {
            window.remove(window.first())
        }
        return true
    }

    companion object {
        private const val REPLAY_WINDOW = 1024L

        /** HKDF-SHA256 (extract + expand) — matches the desktop engine's KDF. */
        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            // extract
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            // expand
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < length) {
                mac.reset()
                mac.update(t)
                mac.update(info)
                mac.update(counter.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n
                counter++
            }
            return out
        }
    }
}

/**
 * Parses and validates the pairing QR payload (spec §Pairing). Refuses to pair on a
 * major protocol-version mismatch.
 */
object Pairing {
    fun parse(qrText: String): Result<PairingInfo> = runCatching {
        val info = FlowJson.decodeFromString(PairingInfo.serializer(), qrText)
        require(info.v == Config.PROTOCOL_VERSION) {
            "protocol version mismatch: peer=${info.v} self=${Config.PROTOCOL_VERSION}"
        }
        val psk = Base64.decode(info.psk, Base64.DEFAULT)
        require(psk.size == Config.Crypto.PSK_BYTES) { "psk must be ${Config.Crypto.PSK_BYTES} bytes" }
        info
    }

    fun pskBytes(info: PairingInfo): ByteArray = Base64.decode(info.psk, Base64.DEFAULT)
}

/**
 * Owns the OkHttp client, the encrypted channel, and the protocol handlers.
 * One instance per process (held by [IndexingService]); connect once per paired peer.
 */
class FederationManager(
    private val selfDeviceId: String,
    private val selfName: String,
    private val index: Index,
    private val caps: Caps
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)       // heartbeat (spec §4.2)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val peers = ConcurrentHashMap<String, OkHttpPeer>()

    /** Pending QUERY correlation: query_id -> collector. */
    private val pending = ConcurrentHashMap<String, QueryCollector>()

    /** UI/Service hook for proactive recall (SURFACE) and incoming results. */
    var onSurface: ((Surface) -> Unit)? = null

    /**
     * Dial a peer described by a scanned QR. Auto-reconnect is left as a TODO hook
     * (re-invoke connect() on onFailure with backoff).
     */
    fun connect(info: PairingInfo) {
        val channel = AeadChannel(Pairing.pskBytes(info))
        val url = "ws://${info.ip}:${info.port}/flow"
        val request = Request.Builder().url(url).build()
        val peer = OkHttpPeer(channel)
        val ws = client.newWebSocket(request, PeerListener(peer))
        peer.attach(ws)
        Log.i(TAG, "dialing $url")
    }

    /**
     * Federated ask (spec §5.1 client side): broadcast QUERY to all peers and collect
     * RESULTS within the timeout. Local search is done by the caller and fused via [Fusion].
     */
    fun broadcastQuery(text: String, topK: Int = Config.TOP_K): String {
        val queryId = UUID.randomUUID().toString()
        // Snapshot the fan-out target count so the asker can stop polling as soon as every
        // peer it broadcast to has replied (spec §4.4: partial allowed, return early).
        val targets = peers.values.toList()
        pending[queryId] = QueryCollector(expectedPeers = targets.size)
        val q = FlowJson.encodeToString(Query.serializer(), Query(query_id = queryId, text = text, top_k = topK))
        targets.forEach { it.send(q) }
        return queryId
    }

    /** Block-free collection: returns whatever RESULTS arrived for [queryId] so far. */
    fun collectedHits(queryId: String): List<Hit> = pending[queryId]?.hits().orEmpty()

    /** True once every peer this query was broadcast to has returned its RESULTS. */
    fun allPeersReplied(queryId: String): Boolean = pending[queryId]?.isComplete() ?: true

    fun finishQuery(queryId: String) { pending.remove(queryId) }

    private inner class PeerListener(private val peer: OkHttpPeer) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // HELLO handshake.
            val hello = Hello(device_id = selfDeviceId, name = selfName, caps = caps)
            peer.send(FlowJson.encodeToString(Hello.serializer(), hello))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val plaintext = peer.channel.open(text) ?: return   // decrypt; drop bad frames
            handle(plaintext, peer)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "peer ${peer.deviceId} failed: ${t.message}")
            peers.remove(peer.deviceId)
            // TODO: reconnect with exponential backoff (spec §4.2 "survive a Wi-Fi blip").
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            peers.remove(peer.deviceId)
        }
    }

    /** Dispatch an inbound plaintext message by its `type`. */
    private fun handle(plaintext: String, peer: OkHttpPeer) {
        when (Wire.peekType(plaintext)) {
            MsgType.HELLO -> {
                val hello = FlowJson.decodeFromString(Hello.serializer(), plaintext)
                peer.deviceId = hello.device_id
                peers[hello.device_id] = peer
                Log.i(TAG, "paired with ${hello.name} (${hello.device_id})")
            }
            MsgType.QUERY -> {
                // A peer asked us — search OUR index only and reply with hits (snippets+thumbs).
                val q = FlowJson.decodeFromString(Query.serializer(), plaintext)
                val qv = StubEmbedHook.embed?.invoke(q.text) ?: return
                val hits = index.searchText(qv, q.top_k)
                val res = Results(query_id = q.query_id, device_id = selfDeviceId, hits = hits)
                peer.send(FlowJson.encodeToString(Results.serializer(), res))
            }
            MsgType.RESULTS -> {
                val r = FlowJson.decodeFromString(Results.serializer(), plaintext)
                pending[r.query_id]?.add(r.hits)   // also counts this peer as having replied
            }
            MsgType.FETCH -> {
                val f = FlowJson.decodeFromString(Fetch.serializer(), plaintext)
                val item = index.get(f.item_id)
                // TODO: read the real file at item.file_ref and base64 it. Thumb fallback for now.
                val blob = item?.thumb_b64 ?: ""
                val fr = FetchResult(item_id = f.item_id, mime = "image/jpeg", blob_b64 = blob)
                peer.send(FlowJson.encodeToString(FetchResult.serializer(), fr))
            }
            MsgType.FETCH_RESULT -> {
                // Hydrated lazily on open; the UI layer subscribes via its own callback. (TODO)
            }
            MsgType.SURFACE -> {
                onSurface?.invoke(FlowJson.decodeFromString(Surface.serializer(), plaintext))
            }
            else -> Log.w(TAG, "unknown message type")
        }
    }

    fun shutdown() {
        peers.values.forEach { it.close() }
        peers.clear()
        client.dispatcher.executorService.shutdown()
    }

    /** Concrete OkHttp-backed peer link; seals every outbound frame. */
    private class OkHttpPeer(val channel: AeadChannel) : PeerLink {
        override var deviceId: String = "pending"
        private var ws: WebSocket? = null
        fun attach(socket: WebSocket) { ws = socket }
        override fun send(plaintextJson: String) { ws?.send(channel.seal(plaintextJson)) }
        override fun close() { ws?.close(1000, "bye") }
    }
}

/**
 * Bridge so [FederationManager] can embed a peer's QUERY text without taking a hard
 * dependency on [Inference]. [IndexingService] sets this to `inference::embedText`.
 */
object StubEmbedHook {
    @Volatile var embed: ((String) -> FloatArray)? = null
}

/**
 * Accumulates RESULTS hits for one query_id (partial results allowed; spec §4.4).
 *
 * Tracks how many of the [expectedPeers] have replied so the asker can stop waiting
 * early once everyone has answered instead of always blocking for the full timeout.
 */
private class QueryCollector(private val expectedPeers: Int) {
    private val all = ArrayList<Hit>()
    private var replied = 0
    @Synchronized fun add(hits: List<Hit>) { all.addAll(hits); replied++ }
    @Synchronized fun hits(): List<Hit> = ArrayList(all)
    /** True once every broadcast target has replied (vacuously true when there are no peers). */
    @Synchronized fun isComplete(): Boolean = replied >= expectedPeers
}

/** Stable per-install device id derived from a random seed; persisted by the caller. */
fun deriveDeviceId(seed: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return Base64.encodeToString(md.digest(seed.toByteArray()), Base64.NO_WRAP or Base64.URL_SAFE).take(16)
}
