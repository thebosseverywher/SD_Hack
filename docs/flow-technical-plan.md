# Flow — Technical Plan
### Snapdragon Multiverse Hackathon · created June 23, 2026
Companion to `flow-plan.md` (this is Workstream C in detail) and `flow-proposal.md`.

---

## 1. System architecture

Two full peer nodes — **Android (Snapdragon 8 Elite)** and **Windows on ARM (Snapdragon X Elite)** — each running the same five layers:

```
        ┌─────────────────────────────────────────────┐
        │  UI  (query in: voice/text · results · telemetry) │
        ├─────────────────────────────────────────────┤
        │  Brain  (Ask = RAG · Surface = rules · Act)   │
        ├─────────────────────────────────────────────┤
        │  Index  (sqlite-vec: vectors + metadata)      │
        ├─────────────────────────────────────────────┤
        │  Inference  (NPU: OCR · embeddings · LLM)      │
        ├─────────────────────────────────────────────┤
        │  Sensors  (Trove · Trail · Files · …)          │
        └─────────────────────────────────────────────┘
              ▲  federation: WebSocket over local Wi-Fi  ▲
              └───────── QR-paired, AEAD-encrypted ───────┘
```

**Symmetric:** either node can originate a query; retrieval fans out to both; the asking node composes the answer. Nothing but a query + small result snippets crosses the wire.

---

## 2. Stack per platform

### Android (Kotlin)
- App: Kotlin, AndroidX, a foreground Service for background indexing.
- Sensors: `MediaStore` + `ContentObserver` (photos), `AccessibilityService` (activity/typed text), `NotificationListenerService` (Sieve, stretch), Storage Access Framework (Files folder pick), CameraX (live capture).
- Inference: **ONNX Runtime Android + QNN Execution Provider** (NPU) for OCR/embeddings/CLIP; **Genie (QNN GenAI runtime)** for the on-phone LLM (AI Hub ships precompiled Llama-3.2-3B for Snapdragon).
- Index: **sqlite-vec** (via the Android SQLite).
- Net: OkHttp or Ktor WebSocket client/server; Android NSD for discovery.

### Windows on ARM (X Elite) — **recommended: Python-first**
- Engine: Python 3.11 — FastAPI + `websockets` (federation), **ONNX Runtime + QNN EP** (embeddings/OCR/CLIP), LLM via **ONNX Runtime GenAI (QNN)** or **Genie**, **sqlite-vec**.
- Activity capture (Trail): `uiautomation` / `pywin32` — `SetWinEventHook(EVENT_SYSTEM_FOREGROUND)` for active-app timeline; UIA tree for content (stretch).
- UI: a local web app (React or plain) served by the engine, or a **Tauri** shell for a native installable feel.
- Packaging: PyInstaller → `.exe`, wrapped as **`.MSIX`** for the 20-pt "ease of install" criterion.
- **Alternative (if the team is strong in C#):** .NET 8 + WinUI 3 — native `System.Windows.Automation` (UIA), cleanest MSIX, ORT C# + QNN. Pick Python for ML velocity + code reuse with the rest of the stack; pick C# only if native UIA + installer polish outweigh that.

---

## 3. On-device AI layer (the part that wins/loses the 40-pt score)

| Task | Open-source model | Runtime (NPU path) | Maturity |
|---|---|---|---|
| Text embeddings | `all-MiniLM-L6-v2` or `bge-small-en` | ORT + QNN EP | **well-trodden** |
| OCR | AI Hub OCR (EasyOCR/PaddleOCR-class) | ORT/QNN or TFLite + QNN delegate | well-trodden |
| Image embeddings | OpenCLIP ViT-B/32 | ORT + QNN EP | well-trodden |
| Utility-photo classify | zero-shot via CLIP text prompts ("a wifi password sticker", "a parking spot") | ORT + QNN EP | well-trodden |
| Doc text extract | PyPDF / pdfminer / Tika (CPU is fine) | — | trivial |
| **LLM — laptop** | Llama-3.1-8B / Phi-3.5 / Qwen2.5-7B, INT4 | **Genie/QNN** or ORT-GenAI+QNN | **VALIDATE (risk #1)** |
| **LLM — phone** | Llama-3.2-3B / Phi-3.5-mini / Qwen2.5-3B, INT4 | **Genie/QNN** (AI Hub precompiled) | **VALIDATE (risk #1)** |
| ASR (audio, stretch) | Whisper (AI Hub sample) | ORT/QNN | provided sample |

**Honest split:** embeddings, OCR, and CLIP on the NPU via ORT-QNN are well-supported and low-risk (AI Hub provides the models). **Running an LLM on the NPU is the one genuinely fiddly integration** — candidate paths, in order to try: (1) Genie / QNN GenAI runtime with an AI Hub-precompiled model, (2) ONNX Runtime GenAI with the QNN EP, (3) the AnythingLLM Qualcomm-NPU backend (the provided sample), (4) **fallback: MLC-LLM on the Adreno GPU** (not NPU but works), or (5) **architectural fallback: the phone always routes generation to the laptop when co-located.** Validate in Spike 0 (this week) so the architecture is settled before the onsite.

RAG keeps the LLM's job small (phrase retrieved snippets), so even the phone's 3B model is sufficient for grounded answers — which is why on-NPU LLM is about latency/energy, not raw capability.

---

## 4. Data model (one row per indexed item, in sqlite-vec)

```sql
CREATE TABLE items (
  id TEXT PRIMARY KEY,
  device_id TEXT,            -- which node owns it
  source TEXT,               -- trove | trail | files | sieve | ...
  ts INTEGER,                -- unix time
  app_context TEXT,          -- e.g. "IRCTC", "Chrome/proposal.docx"
  text TEXT,                 -- OCR'd / extracted / transcript text
  type TEXT,                 -- wifi | parking | receipt | serial | poster | event | doc | activity ...
  fields JSON,               -- extracted structured fields
  thumb BLOB,                -- small jpeg for images (nullable)
  file_ref TEXT              -- path on origin device for fetch-on-open
);
-- vector index (sqlite-vec virtual table) over the text/image embedding
CREATE VIRTUAL TABLE vec_items USING vec0(embedding FLOAT[384]);
```

Passwords/OTP fields are dropped at capture time (never written). A visible "capturing" indicator is shown.

---

## 5. Federation protocol (JSON over WebSocket, AEAD-encrypted with QR-derived key)

```
PAIR     {ip, port, key}                         // exchanged via QR at setup
QUERY    {query_id, text, top_k}                 // broadcast to peers
RESULTS  {query_id, device_id, hits:[            // each peer replies
            {item_id, score, source, text, type, fields, thumb_b64?}
         ]}
FETCH        {item_id}                            // pull full file on open
FETCH_RESULT {item_id, mime, blob_b64}
SURFACE  {event, payload}                         // push, proactive (Surface mode)
```

**Discovery:** QR pairing (laptop shows QR with ip/port/key) for a reliable demo; NSD/mDNS auto-discovery as a stretch nicety.

---

## 6. Query flow (Ask = federated RAG)

1. User asks on node **D** (text, or voice → on-device ASR).
2. **D** embeds the query on its NPU.
3. **D** searches its own `vec_items` (ANN) **and** sends `QUERY` to peer(s).
4. Each node returns top-k `hits` (snippet + thumbnail + metadata + score) — **never raw data**.
5. **D** fuses + re-ranks all hits.
6. **D** builds a RAG prompt from the top hits → local LLM → answer **with citations** (source device + open-on-tap file).
7. Each stage logs to the **telemetry** panel.

**Surface (proactive):** lightweight rules over new items / context (e.g., on "leaving mall" geofence → recall the latest `type=parking` item). **Act:** map an answer to an OS intent (create calendar event, set reminder).

---

## 7. Telemetry panel (make the NPU visible)

Per inference, record: execution provider (NPU/CPU/GPU), latency (ms), tokens/sec (LLM), and utilization where exposed. **The hero control: an NPU↔CPU toggle** that re-runs the same model on the CPU EP vs the QNN EP and shows the latency delta live. (Power draw is hard to read precisely on-device — show latency + tokens/sec + EP + utilization, and present energy qualitatively; don't fake a watts number.)

---

## 8. Repo layout (monorepo, MIT-licensed)

```
flow/
  android/          # Kotlin app: sensors, index, inference, UI
  desktop/          # Python engine + UI (or C#/.NET): sensors, index, inference, telemetry
  shared/           # protocol JSON schemas, prompt templates, model configs
  models/           # AI Hub compile/quantize scripts, model cards
  docs/             # README (setup from scratch, run, deps), architecture
  LICENSE           # MIT
  README.md
```
(Android Kotlin and desktop Python/C# don't share runtime code; `shared/` is protocol + prompts + configs.)

---

## 9. Build sequence — spikes with exit criteria (de-risk hardest-first)

- **Spike 0 — AI layer (this week, RISK #1).** On any Snapdragon phone + the X Elite: get embeddings + OCR running on the NPU via ORT-QNN, **and** a 3B LLM generating tokens on the phone NPU (Genie/QNN) + a 7–8B on the laptop. *Exit:* tokens/sec measured on NPU; if the phone LLM underperforms, lock the "route-to-laptop" fallback now.
- **Spike 1 — federation skeleton.** WebSocket + QR pair + encrypted `QUERY`/`RESULTS` echo between two machines (any hardware). *Exit:* a query round-trips and returns dummy hits.
- **Spike 2 — Trove vertical slice.** Photo → OCR + CLIP embed → sqlite-vec → query → LLM answer, on one device. *Exit:* "where did I park?" returns the right photo + answer.
- **M1 — federate the slice:** ask on laptop, both devices return Trove hits, answer composed locally.
- **M2 — add Trail + Files** into the same index; symmetric query (ask from either device); telemetry v1.
- **M3 — Surface scenario + Act + installers (.APK/.MSIX) + README;** rehearse.
- **M4 (onsite) — port to loaner 8 Elite + X Elite; integrate + optimize AI Hub models on their NPUs;** add one support feature if ahead; 3 rehearsed demo acts; Final Submission.

---

## 10. Decisions to lock now

1. **Desktop language:** Python-first (recommended — ML velocity, reuse) vs C#/.NET (native UIA, cleaner MSIX). 
2. **LLM-on-NPU runtime:** decide after Spike 0 (Genie/QNN vs ORT-GenAI vs AnythingLLM vs GPU fallback).
3. **Models:** confirm the AI Hub-precompiled LLMs (Llama-3.2-3B phone / 8B laptop) and embedding model (`all-MiniLM-L6-v2`, 384-dim → matches the schema).
4. **Vector store:** sqlite-vec (cross-platform, one file) — confirmed unless ObjectBox is preferred on Android.
5. **Index dimension:** fix the embedding dim (e.g., 384) across both nodes so vectors are comparable in federated results.
```
