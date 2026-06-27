# Flow — Detailed Technical Specification (Work Breakdown)
### Snapdragon Multiverse Hackathon · created June 23, 2026
Expands `flow-technical-plan.md`. Each leaf component lists: **Purpose · Requirements · Stack/APIs · Depends on · Tasks · Done when.**

**Legend** — Tier: **P0** = committed core (must ship), **P1** = support (if ahead), **P2** = stretch. Owner roles: **BE** backend/federation+lead, **AND** Android, **WIN** desktop, **ML** models, **UX** UI/demo.

---

## Global contracts (freeze these first — everything depends on them)

```
PROTOCOL_VERSION = 1
TEXT_DIM   = 384         # all-MiniLM-L6-v2  (text/semantic space)
IMAGE_DIM  = 512         # OpenCLIP ViT-B/32 (image/zero-shot space)
WS_PORT    = 8787        # federation websocket
SOURCES    = [trove, trail, files, sieve, relay, threads, audio]
TYPES      = [wifi, parking, receipt, serial, poster, event, doc, activity, note, contact, other]
TOP_K      = 8           # per-node retrieval
```
- **Two embedding spaces** are intentional: photo OCR text + all other text → 384-dim (MiniLM); photo *images* → 512-dim (CLIP) for visual/zero-shot search. They are searched separately and fused. Do not mix dims.
- All cross-device payloads conform to `shared/protocol.schema.json` (§0.2).

**Build/dependency order:** `0 (foundations) → 1 (inference) + 4.1–4.3 (transport) in parallel → 2 (index) → 3.1 Trove + 5.1 Ask (vertical slice = M1) → 3.2/3.3 Trail + 3.4 Files (M2) → 5.2/5.3 + 6.3 telemetry (M2/M3) → 7 packaging (M3) → onsite (M4).`

---

# 0. Foundations (cross-cutting) — P0, owner BE

### 0.1 Monorepo & build tooling — P0 · BE
**Purpose:** one repo, reproducible builds for three toolchains.
**Requirements:** Android (Gradle/Kotlin), desktop (Python 3.11 arm64, `uv` or Poetry), shared schema package; CI that produces a signed `.APK` and an `.MSIX` artifact.
**Stack/APIs:** Git, Gradle 8.x, `uv`, GitHub Actions (build matrix: android, win-arm64).
**Depends on:** —
**Tasks:**
- [ ] Create tree: `android/ desktop/ shared/ models/ docs/ LICENSE README.md`
- [ ] Add MIT `LICENSE`; README skeleton with the rule-mandated sections (§7.3)
- [ ] Pre-commit (ktlint, ruff, black), `.editorconfig`
- [ ] CI: build APK + MSIX on push
**Done when:** `git clone` → documented one-command build yields both installers.

### 0.2 Shared protocol & schemas — P0 · BE
**Purpose:** single source of truth for all messages and the item model.
**Requirements:** JSON Schema for every message type + the `Item` record; versioned (`PROTOCOL_VERSION`); language-neutral (consumed by Kotlin + Python).
**Stack/APIs:** JSON Schema draft 2020-12; codegen optional (`quicktype`).
**Interface:** files `shared/protocol.schema.json`, `shared/item.schema.json`, `shared/prompts/*.txt`, `shared/config.json` (dims, port, enums).
**Depends on:** Global contracts.
**Tasks:**
- [ ] Define `Item`, `Query`, `Results`, `Hit`, `Fetch`, `FetchResult`, `Surface`
- [ ] Publish enums (SOURCES, TYPES) and constants here, imported by both apps
**Done when:** both apps validate sample payloads against the schema in a unit test.

### 0.3 Dev environment & hardware — P0 · all
**Purpose:** every dev can run NPU inference locally.
**Requirements:** Android Studio + NDK r26+; Windows on ARM64 with Python arm64; **Qualcomm AI Engine Direct (QNN) SDK**; **ONNX Runtime ≥1.18 with QNN EP**; **Qualcomm AI Hub** account + `pip install qai-hub`. Dev phones (any Snapdragon for spikes; 8 Elite loaner for M4); X Elite Copilot+ PC.
**Depends on:** —
**Tasks:**
- [ ] QNN SDK + HTP backend libs installed on each machine
- [ ] `qai-hub` configured with API token; verify a sample compile
- [ ] ORT-QNN "hello inference" runs on the NPU on phone + PC
**Done when:** a trivial model runs on the HTP (NPU) on both device classes, EP confirmed via session providers.

### 0.4 Model registry & AI Hub pipeline — P0 · ML
**Purpose:** compile/quantize/profile every model for 8 Elite + X Elite and version the artifacts.
**Requirements:** scripts that pull each model, compile via AI Hub to QNN context binary / ONNX, profile latency on target, and emit a model card (name, dim, quant, target, tokens/sec).
**Stack/APIs:** `qai_hub.submit_compile_job`, `submit_profile_job`; targets Snapdragon 8 Elite & X Elite.
**Interface:** `models/<name>/{compile.py, model.bin|.onnx, card.md}`.
**Depends on:** 0.3.
**Tasks:**
- [ ] MiniLM-L6-v2 (text emb, 384) — compile + profile both targets
- [ ] OCR model(s) — detection + recognition
- [ ] OpenCLIP ViT-B/32 (image+text encoders, 512)
- [ ] Llama-3.2-3B INT4 (phone), Llama-3.1-8B / Phi-3.5 INT4 (laptop)
**Done when:** all artifacts profiled with recorded NPU tokens/sec & latency in their cards.

### 0.5 Security / crypto channel — P0 · BE
**Purpose:** pair devices and encrypt all traffic without a cloud.
**Requirements:** QR encodes `{ip, port, psk(32B)}`; derive a session key via HKDF; wrap all WS frames in AEAD; nonce per message; reject replays.
**Stack/APIs:** libsodium (`XChaCha20-Poly1305`/secretbox) or AES-256-GCM; HKDF-SHA256.
**Interface:** `seal(plaintext)->frame`, `open(frame)->plaintext`.
**Depends on:** Global contracts.
**Tasks:**
- [ ] Key derivation from psk; AEAD wrap/unwrap; monotonic nonce + replay window
**Done when:** tampered/replayed frames are rejected; round-trip verified in tests.

### 0.6 Config & logging — P0 · BE
**Purpose:** consistent config + structured logs (and a feed for telemetry §6.3).
**Requirements:** load `shared/config.json`; structured logging with per-inference timing events.
**Tasks:** [ ] config loader both apps · [ ] timing-event log schema (used by telemetry).
**Done when:** an inference emits a structured `{stage, ep, ms, tokens_s}` event.

---

# 1. Inference layer (on-device AI) — owner ML (+AND/WIN bindings)

### 1.0 Inference abstraction & EP switching — P0 · ML
**Purpose:** one interface for all models; can run a model on **QNN (NPU)** or **CPU** to power the telemetry toggle.
**Requirements:** factory that builds a session with a chosen EP; expose last-run latency; thread-safe; warm-up on init.
**Stack/APIs:** ORT `SessionOptions`, providers `["QNNExecutionProvider"]` (htp) vs `["CPUExecutionProvider"]`.
**Interface:** `Model.load(path, ep)`, `Model.run(inputs)->(outputs, latency_ms)`.
**Depends on:** 0.3, 0.4.
**Done when:** same model runs on NPU and CPU; latency delta observable.

### 1.1 Text embedding service — P0 · ML
**Purpose:** turn text into 384-d vectors.
**Requirements:** tokenization (max 256 tok), mean-pooling, L2-normalize; batch ≤32; <15 ms/sentence on NPU target.
**Stack/APIs:** all-MiniLM-L6-v2 ONNX; HF `tokenizers` (Rust, has Kotlin/Python bindings).
**Interface:** `embed_text(List[str]) -> float[N][384]`.
**Depends on:** 1.0.
**Done when:** cosine of paraphrases > 0.7; batch throughput recorded.

### 1.2 OCR service — P0 · ML/AND
**Purpose:** extract text + boxes from a photo.
**Requirements:** detection + recognition; handle rotated/low-light; return text, boxes, confidence; multilingual Latin + (stretch) Devanagari.
**Stack/APIs:** AI Hub OCR (EasyOCR/PaddleOCR-class) via ORT-QNN or TFLite + QNN delegate.
**Interface:** `ocr(image) -> List[{text, box, conf}]`.
**Depends on:** 1.0.
**Done when:** reads a wifi sticker + a printed serial correctly in a test set.

### 1.3 Image embedding & zero-shot classify (CLIP) — P0 · ML
**Purpose:** 512-d image vectors for visual search + utility-photo typing.
**Requirements:** image encoder + text encoder; zero-shot `type` via prompt set ("a photo of a wifi password", "a parking spot", "a receipt"…); cache text-prompt embeddings.
**Stack/APIs:** OpenCLIP ViT-B/32 ONNX via ORT-QNN.
**Interface:** `embed_image(img)->float[512]`, `classify_type(img)->{type, score}`.
**Depends on:** 1.0.
**Done when:** ≥80% type accuracy on a 50-photo hand-labeled set.

### 1.4 LLM runtime — P0 · ML (**RISK #1**)
**Purpose:** generate grounded answers on-device, streaming.
**Requirements:** phone = 3B INT4; laptop = 7–8B INT4; streaming tokens; stop sequences; context ≥4k; expose tokens/sec.
**Stack/APIs:** **Genie (QNN GenAI)** with AI Hub-precompiled models; alternates: ORT-GenAI+QNN, AnythingLLM NPU backend; fallbacks: MLC-LLM (Adreno GPU), or route-to-laptop.
**Interface:** `generate(prompt, max_tokens, stop, stream=True) -> Iterator[token]`.
**Depends on:** 0.4, 1.0.
**Tasks:** [ ] phone 3B on NPU · [ ] laptop 8B on NPU · [ ] streaming + tokens/sec · [ ] pick runtime after Spike 0.
**Done when:** both nodes stream ≥ a usable tokens/sec on NPU; fallback decided if not.

### 1.5 Document text extraction — P0 · WIN/AND
**Purpose:** text out of PDFs/docx for Files.
**Requirements:** PDF, docx, txt, md; chunk 512 tok / 64 overlap; CPU acceptable.
**Stack/APIs:** `pypdf`/`pdfminer.six`, `python-docx`, Apache Tika (optional); Android: PdfRenderer + text or a Kotlin PDF lib.
**Interface:** `extract(path) -> List[{chunk_text, page}]`.
**Done when:** a 10-page PDF yields clean chunked text.

### 1.6 ASR (audio) — P2 · ML
**Purpose:** transcribe captured audio (stretch sensor).
**Stack/APIs:** Whisper (AI Hub sample) via ORT-QNN. **Interface:** `transcribe(wav)->text`. **Done when:** clean transcript on a 30-s clip.

---

# 2. Index / storage layer — owner BE (+AND/WIN ports)

### 2.1 Schema & migrations — P0 · BE
**Purpose:** one local store for items + two vector spaces.
**Stack/APIs:** SQLite + **sqlite-vec** (`vec0`), same schema on both platforms.
```sql
items(id PK, device_id, source, ts, app_context, text, type, fields JSON, thumb BLOB, file_ref);
vec_text  USING vec0(embedding FLOAT[384]);   -- id ↔ items.id
vec_image USING vec0(embedding FLOAT[512]);
```
**Done when:** migrations run idempotently on fresh + existing DB.

### 2.2 Ingestion API — P0 · BE
**Purpose:** write items + vectors, dedupe, exclude secrets.
**Requirements:** upsert by content hash; reject items whose source field was a password/OTP (set at capture); transactional (item + vector together).
**Interface:** `ingest(Item) -> id`; `ingest_batch(List[Item])`.
**Depends on:** 2.1.
**Done when:** duplicate ingest is a no-op; password-flagged text never persisted.

### 2.3 Vector search — P0 · BE
**Purpose:** top-k ANN with filters, in both spaces.
**Requirements:** cosine; filter by `source/type/time`; return `Hit{item_id, score, source, text, type, fields, thumb?}`; <30 ms for 10k items.
**Interface:** `search_text(vec384, k, filters)`, `search_image(vec512, k, filters)`.
**Depends on:** 2.1.
**Done when:** known item is top-1 for its own query across 5k seeded items.

### 2.4 Blob/thumbnail store — P0 · BE
**Purpose:** small thumbs inline; full files fetched on demand.
**Requirements:** thumbs ≤ 64 KB jpeg in `items.thumb`; full file resolved via `file_ref` only on `FETCH`.
**Done when:** results carry thumbs; full image retrievable via §4.4.

---

# 3. Sensors layer (capture)

### 3.1 Trove — photo capture & utility pipeline — P0 · AND
**Purpose:** turn the camera roll into indexed, typed, actionable items.
**Requirements:** index existing library (backfill) + observe new photos; per photo run OCR (1.2) + CLIP type (1.3) + image emb (1.3) + text emb of OCR (1.1) + field extraction; store both vectors. Must not block UI; battery-aware (batch when charging/idle).
**Stack/APIs:** `MediaStore` + `ContentObserver`; `WorkManager` for backfill; CameraX for live capture; perm `READ_MEDIA_IMAGES`.
**Field extraction:** regex for wifi (SSID/pass), amounts, dates, serials, phone/email; small-LLM fallback for posters→event fields.
**Interface:** emits `Item{source:trove, type, text, fields, thumb, file_ref}`.
**Depends on:** 1.1–1.3, 2.2.
**Done when:** snap a wifi sticker → `type:wifi, fields:{ssid,pass}` indexed < few s; "where did I park" returns the right photo.

### 3.2 Trail (Android) — activity & routine — P0 · AND
**Purpose:** private timeline of what you did on the phone.
**Requirements:** capture foreground app (`TYPE_WINDOW_STATE_CHANGED` → package/label), typed text (`TYPE_VIEW_TEXT_CHANGED`, **skip `isPassword()` / `TYPE_TEXT_VARIATION_PASSWORD`**), and confirmation/booking screens (`TYPE_WINDOW_CONTENT_CHANGED`). Coalesce into activity events; visible "capturing" indicator.
**Stack/APIs:** `AccessibilityService` (config: `typeAllMask`, `flagRetrieveInteractiveWindows`, `canRetrieveWindowContent=true`); runtime enable flow (deep-link to Settings).
**Interface:** emits `Item{source:trail, type:activity, app_context, text, ts}`.
**Depends on:** 1.1, 2.2.
**Done when:** app-switch timeline + a typed note appear in the index; password field never captured.

### 3.3 Trail (Windows) — activity & routine — P0 · WIN
**Purpose:** same timeline on the laptop.
**Requirements:** foreground-app timeline via `SetWinEventHook(EVENT_SYSTEM_FOREGROUND)` + process/title; (P1) UIA content via `uiautomation`; avoid raw keyboard hooks for the demo (AV-flag risk).
**Stack/APIs:** `pywin32` (win32gui/win32process), `uiautomation`.
**Interface:** emits `Item{source:trail, type:activity, app_context, text, ts}`.
**Done when:** switching apps/docs logs a clean timeline; no AV warning.

### 3.4 Files — folder indexing (both) — P0 · WIN/AND
**Purpose:** semantic search over chosen documents.
**Requirements:** user picks a folder (SAF on Android `ACTION_OPEN_DOCUMENT_TREE`; folder dialog on Windows); extract (1.5) → chunk → embed (1.1) → ingest; watch for changes.
**Interface:** emits `Item{source:files, type:doc, text:chunk, file_ref, fields:{page}}`.
**Depends on:** 1.1, 1.5, 2.2.
**Done when:** "find my warranty" returns the right PDF chunk + opens the file.

### 3.5 Sieve — notifications — P1 · AND
**Purpose:** triage + index phone notifications.
**Stack/APIs:** `NotificationListenerService`; classify importance (small model/rules).
**Done when:** notifications indexed; "what did I miss" answerable; only important ones forwarded to laptop.

### 3.6 Relay — semantic clipboard — P1 · AND/WIN
**Purpose:** cross-device clipboard history + transform.
**Stack/APIs:** Android `ClipboardManager`, Windows clipboard API; sync via §4.
**Done when:** copy on phone → paste on laptop; snap poster → paste event.

### 3.7 Threads — commitments & plans — P1 · BE
**Purpose:** extract promises/dates from any source into reminders.
**Requirements:** LLM extraction over new items → `Item{type:event/note, fields:{when, who, what}}`; feeds Surface.
**Done when:** "what did I agree to this week" lists real extracted commitments.

---

# 4. Federation / networking — owner BE

### 4.1 Discovery & pairing — P0 · BE/UX
**Purpose:** connect two nodes with zero config.
**Requirements:** laptop creates session, renders QR `{ip,port,psk}`; phone scans; persist peer; (P1) NSD/mDNS auto-discovery.
**Stack/APIs:** `qrcode` (gen), CameraX + ML Kit/ZXing (scan), Android NSD.
**Done when:** scan → paired peer shown on both.

### 4.2 Transport — P0 · BE
**Purpose:** reliable peer-to-peer messaging.
**Requirements:** each node runs a WS **server + client**; auto-reconnect; heartbeat; backpressure.
**Stack/APIs:** desktop FastAPI + `websockets`; Android Ktor/OkHttp WS.
**Interface:** `send(peer, msg)`, `on(msg_type, handler)`.
**Done when:** messages survive a Wi-Fi blip (reconnect).

### 4.3 Encrypted channel — P0 · BE
**Purpose:** wrap all frames per 0.5. **Done when:** sniffing the LAN shows only ciphertext.

### 4.4 Protocol handlers — P0 · BE
**Purpose:** implement QUERY/RESULTS/FETCH/SURFACE.
**Requirements:** correlate by `query_id`; per-query timeout (e.g., 1.5 s) — return partial if a peer is slow; `FETCH` streams a file blob.
**Interface (messages):**
```
QUERY{query_id,text,top_k}  →  RESULTS{query_id,device_id,hits[]}
FETCH{item_id}              →  FETCH_RESULT{item_id,mime,blob_b64}
SURFACE{event,payload}      (push)
```
**Done when:** a query from either node returns fused hits from both within timeout.

### 4.5 Result fusion & re-ranking — P0 · BE
**Purpose:** merge local + peer hits into one ranked list.
**Requirements:** normalize scores across nodes/spaces; fuse text+image hits (e.g., Reciprocal Rank Fusion); dedupe; tag source device.
**Interface:** `fuse(List[Hits]) -> ranked List[Hit]`.
**Done when:** cross-source query surfaces the right items from both devices, ordered sensibly.

### 4.6 Compute routing — P2 · BE/ML
**Purpose:** run answer-generation on the best idle NPU.
**Requirements:** capability exchange (TOPS/battery/idle); route `generate` to peer when it's stronger; return tokens to the asker. **Done when:** ask on phone → laptop generates → phone displays.

---

# 5. Brain layer — owner BE

### 5.1 Ask (federated RAG) — P0 · BE
**Purpose:** answer questions with citations.
**Requirements:** embed query (text→1.1, also CLIP-text→1.3 for photos); fan-out (§4); fuse (4.5); build context with source tags; LLM (1.4) stream; parse citations → `{answer, sources[]}`; graceful "nothing found".
**Interface:** `ask(text) -> {answer, sources:[{item_id, device, file_ref}]}`.
**Depends on:** 1.1,1.3,1.4,2.3,4.4,4.5,5.5.
**Done when:** all three demo queries answer correctly with right citations.

### 5.2 Surface (proactive) — P0 (1 scenario) · BE/AND
**Purpose:** recall before being asked.
**Requirements:** rule engine: trigger (new item type / geofence / time) → action (notify with recalled item). Ship ≥1 scenario (leaving mall → parking recall).
**Interface:** rules as `{trigger, condition, action}`; emits `SURFACE`.
**Done when:** the parking scenario fires a notification end-to-end.

### 5.3 Act (OS intents) — P1 · AND/WIN
**Purpose:** do something with an answer.
**Stack/APIs:** Android calendar `Intent.ACTION_INSERT` (Events), reminders; Windows calendar/reminder.
**Done when:** "add this poster to my calendar" creates a real event.

### 5.4 Query understanding (voice/intent) — P1 · AND/UX
**Purpose:** voice input + light intent routing (search vs act).
**Stack/APIs:** on-device STT (Android SpeechRecognizer offline / Whisper); simple intent classifier.
**Done when:** spoken query → correct path.

### 5.5 Prompts & citation format — P0 · BE
**Purpose:** stable RAG prompt + citation contract.
**Requirements:** templates in `shared/prompts/`; enforce "answer only from context; cite item ids"; truncate context to model window. **Done when:** answers consistently include valid citations.

---

# 6. UI layer — owner UX (+AND/WIN)

### 6.1 Android UI — P0 · AND/UX
**Requirements:** pairing (QR scan), query (text+mic), results (snippet+thumb+source badge, tap→open/Act), capture indicator + per-sensor on/off + consent screen.
**Done when:** full query→answer→open flow on phone.

### 6.2 Desktop UI — P0 · WIN/UX
**Requirements:** pairing (show QR), query, results with source badges, Files/folder picker, sensor toggles.
**Stack/APIs:** local web app (React/plain) or Tauri shell.
**Done when:** full flow on laptop, both ask directions work.

### 6.3 Telemetry panel — P0 · WIN/ML
**Purpose:** make the NPU visible (40-pt criterion).
**Requirements:** live per-stage latency, tokens/sec, EP, utilization if exposed; **NPU↔CPU toggle** re-runs the same model on CPU EP to show the delta. Power shown qualitatively (don't fake watts).
**Depends on:** 0.6, 1.0.
**Done when:** flipping the toggle visibly changes latency live on screen.

### 6.4 Onboarding / consent — P0 · UX
**Requirements:** first-run pairing + permissions + a clear "what's captured / passwords excluded / all on-device" consent screen. **Done when:** a new user reaches a working query in <2 min.

---

# 7. Packaging & deployment — owner WIN/AND

### 7.1 Android APK — P0 · AND
**Requirements:** signed release `.APK`, all perms declared, foreground-service notification. **Done when:** installs on the 8 Elite and runs from cold.

### 7.2 Windows MSIX/EXE — P0 · WIN
**Requirements:** `.MSIX` (preferred) or `.EXE`; bundle model artifacts or first-run fetch; ARM64. **Done when:** double-click install → app runs on X Elite.

### 7.3 README & license — P0 · UX/BE
**Requirements (per rules):** description; all members' names+emails; setup-from-scratch incl. deps; run/usage; MIT license; (recommended) tests, notes, references. **Done when:** a stranger follows the README and runs it.

### 7.4 Tests & testing instructions — P1 · all
**Requirements:** unit tests for schema/index/fusion/crypto; a scripted smoke test ("seed → query → expected hit"). **Done when:** `make test` passes; instructions in README.

---

# 8. Demo & integration — owner UX

### 8.1 Demo data capture — P0 · all
Run Flow on the team from build hour 0 (real photos/activity/files for the cross-source + routine demos).
### 8.2 Demo script & rehearsal — P0 · UX
3 acts (Trove+Files symmetric ask · cross-source · airplane-mode privacy) + telemetry toggle + the "why not Google" answer. Rehearse from M3.
### 8.3 Fallbacks — P0 · UX
Pre-recorded video of each act; seeded DB snapshot; offline-by-default so no venue-Wi-Fi dependency.

---

## Critical path & risk recap
- **Spike 0 (this week):** 1.0 + 1.1 + 1.2 + 1.4 (the LLM-on-NPU probe) — settles risk #1 and the runtime choice.
- **Vertical slice (M1):** 0.x + 1.1–1.3 + 2.x + 3.1 + 4.1–4.5 + 5.1 → "ask, both devices answer, Trove works."
- **Top risks:** 1.4 LLM-on-NPU (fallbacks listed); 3.2/3.3 capture completeness + AV flags; 5.1 RAG answer quality (script hero queries); 7.2 Python→MSIX packaging (start early).
