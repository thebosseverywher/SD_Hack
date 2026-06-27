# Flow — Wire Protocol & Data Contracts (v1)

This is the **single source of truth** both the desktop engine (`desktop/`) and the Android app (`android/`) must implement so they are wire-compatible. Constants live in `shared/config.json`.

## Transport
- **WebSocket** over the local Wi-Fi/LAN, port `ws_port` (8787).
- Each node runs **both a server and a client** (symmetric peer mesh).
- Every frame is **AEAD-encrypted** (XChaCha20-Poly1305) with a key derived (HKDF-SHA256) from the pairing pre-shared key. Plaintext is JSON.

## Pairing
- The pairing initiator (e.g. the laptop) renders a **QR code** encoding:
```json
{ "ip": "192.168.1.20", "port": 8787, "psk": "<base64 32-byte key>", "v": 1 }
```
- The peer scans it, derives the session key, and connects. (mDNS/NSD auto-discovery is an optional later enhancement.)

## Item (the unit stored in every node's local index)
```json
{
  "id": "uuid",
  "device_id": "string",            // origin node
  "source": "trove|trail|files|sieve|relay|threads|audio",
  "ts": 1719500000,                  // unix seconds
  "app_context": "IRCTC | Chrome/proposal.docx | null",
  "text": "extracted/ocr/transcript text",
  "type": "wifi|parking|receipt|serial|poster|event|doc|activity|note|contact|other",
  "fields": { "ssid": "...", "amount": "...", "when": "..." },  // extracted structured fields
  "thumb_b64": "base64 jpeg <=64KB | null",
  "file_ref": "origin-device path for fetch-on-open | null"
}
```
- **Two embedding spaces** (kept separate, never mixed): text/semantic = `text_dim` (384, all-MiniLM); image = `image_dim` (512, CLIP). A photo produces *both* an image embedding and a text embedding of its OCR.
- **Password/OTP fields are never captured or stored.**

## Messages (JSON, inside the encrypted frame)
Every message has `{ "type": <string>, "v": 1, ...payload }`.

| type | direction | payload | meaning |
|---|---|---|---|
| `HELLO` | both | `{ device_id, name, caps }` | post-pair handshake; `caps` = {tops, has_llm, battery} |
| `QUERY` | A→peers | `{ query_id, text, top_k }` | federated search request |
| `RESULTS` | peer→A | `{ query_id, device_id, hits: [Hit] }` | per-node top-k matches |
| `FETCH` | A→peer | `{ item_id }` | request full file for an opened result |
| `FETCH_RESULT` | peer→A | `{ item_id, mime, blob_b64 }` | the full file |
| `SURFACE` | push | `{ event, payload }` | proactive recall notification |

### Hit
```json
{ "item_id": "uuid", "device_id": "string", "score": 0.83,
  "source": "trove", "type": "serial", "text": "snippet",
  "fields": {}, "thumb_b64": "..." }
```
- A node answers a `QUERY` by searching **its own** index only and returning `hits` (snippets + thumbnails, **never** raw libraries). The asking node fuses local + peer hits, re-ranks (e.g. Reciprocal Rank Fusion across the text and image spaces), tags each by `device_id`, and composes the final answer with its local LLM. Full files are pulled lazily via `FETCH`.

## Query → answer flow (Ask)
1. Ask on node D (text or voice→STT).
2. D embeds the query (text→384; also CLIP-text→512 for photos).
3. D searches its own index **and** broadcasts `QUERY` to peers.
4. Peers reply `RESULTS` (within `query_timeout_ms`; partial allowed).
5. D fuses + re-ranks all hits, builds a cited RAG prompt, runs its local LLM.
6. D returns `{ answer, sources: [{item_id, device_id, file_ref}] }`. Each stage is timed for the telemetry panel.

## Versioning
`protocol_version` in `config.json`. Mismatched majors must refuse to pair.
