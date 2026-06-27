# Flow — Android app

The Android node of **Flow**, an on-device, cross-device personal search assistant for
the Snapdragon Multiverse hackathon. This module is a coherent, buildable **skeleton**:
the app structure, UI, federation transport, index, and capture sensors are real and
wired end-to-end; the AI models and NPU execution are clearly-marked TODO stubs with
extension points (see [Where AI Hub models plug in](#where-ai-hub-models-plug-in)).

Target hardware: **Snapdragon 8 Elite** (`minSdk 31`, `compileSdk 35`, `arm64-v8a`).
The app is wire-compatible with the desktop engine via [`shared/protocol.md`](../shared/protocol.md)
and [`shared/config.json`](../shared/config.json).

---

## What's in here

| Area | File | Status |
|---|---|---|
| Wire protocol & constants | `Protocol.kt` | Real — field names match `shared/protocol.md` exactly |
| Local vector store | `Index.kt` | Real interface + **in-memory cosine fallback**; ObjectBox/sqlite-vec are integration points |
| Inference (embed/OCR/CLIP/LLM) | `Inference.kt` | **Stubs** returning deterministic placeholders; ONNX Runtime + QNN EP wiring points marked |
| Federation peer | `Federation.kt` | Real OkHttp WebSocket client + AEAD channel + protocol handlers |
| Trove (photos) | `TroveIndexer.kt` | Real MediaStore observer + WorkManager backfill + regex field extraction |
| Trail (activity) | `FlowAccessibilityService.kt` | Real AccessibilityService; **passwords/OTP skipped** |
| Ask (federated RAG) | `Ask.kt` | Real orchestration over the stubs |
| UI | `MainActivity.kt`, `QrScanner.kt`, `FlowViewModel.kt` | Real Compose UI: pairing, query+results, sensor toggles, consent |
| Foreground service | `IndexingService.kt` | Real `dataSync` foreground service |
| Tests | `app/src/test/...` | Protocol round-trip, index top-1, HKDF, fusion |

---

## Prerequisites

- **Android Studio** Koala (2024.1) or newer.
- **JDK 17** (bundled with recent Android Studio).
- **Android SDK 35** + Build-Tools; an `arm64-v8a` device or emulator. The full demo
  needs a physical **Snapdragon 8 Elite** phone (NPU + real photo library + activity).
- (For real inference) **Qualcomm AI Hub** account, the **QNN SDK**, and an
  **ONNX Runtime ≥ 1.18 with the QNN Execution Provider** build. See `shared/` docs and
  `docs/flow-technical-spec.md` §0.3–0.4.

---

## Open & build

### In Android Studio
1. `File → Open` and select the `android/` folder (this directory).
2. Let Gradle sync. Android Studio restores the Gradle wrapper jar automatically.
3. Pick the `app` run configuration and a device → **Run**.

### From the command line
The binary `gradle/wrapper/gradle-wrapper.jar` is intentionally **not committed**.
Generate the wrapper once with a local Gradle 8.7, then build:

```bash
cd android
gradle wrapper --gradle-version 8.7   # one-time; restores the wrapper jar
./gradlew assembleDebug                # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest            # run the JVM unit tests
```

On Windows use `gradlew.bat` instead of `./gradlew`.

**CI:** `.github/workflows/android-build.yml` does exactly this bootstrap step
(`gradle wrapper --gradle-version 8.7`, pinned to `gradle-wrapper.properties`) before
running `testDebugUnitTest` and `assembleDebug`, so a clean checkout builds in CI without
the wrapper jar in VCS.

Install the debug APK:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release APK (spec §7.1)
Add a signing config to `app/build.gradle.kts` (`signingConfigs { create("release") { … } }`
referencing your keystore), wire it into the `release` build type, then:
```bash
./gradlew assembleRelease
```

---

## First run

1. **Privacy screen** (first tab): read what's captured, then **Grant permissions**
   (photos, camera, notifications).
2. **Enable Trail**: tap *Enable Trail (Accessibility)*, which deep-links to
   `Settings → Accessibility → Flow Trail`. Toggle it on. (Android requires the user to
   enable an AccessibilityService manually; the app cannot do it programmatically.)
3. **Pair**: on the laptop, start the desktop engine and show its pairing QR. On the
   phone, open the **Pair** tab and scan it. The QR encodes `{ ip, port, psk, v }`
   (`shared/protocol.md` §Pairing); the phone derives the session key and connects.
4. **Ask**: type a query (e.g. *"wifi password"*, *"where did I park"*). The phone
   searches its local index, fans the `QUERY` out to the paired laptop, fuses the hits
   (RRF), and renders results with a **device badge + thumbnail**, plus a (stubbed)
   composed answer.

A persistent **"Flow is capturing on-device"** notification indicates the foreground
indexing service is running.

---

## Privacy model (consent)

- Everything is processed **on-device**. No cloud.
- **Trove** indexes your photos: OCR text, a CLIP-derived type, a ≤64 KB thumbnail.
- **Trail** records foreground apps and typed text as a private timeline.
- **Passwords and OTP fields are never captured.** `FlowAccessibilityService` drops any
  node that is `isPassword`, `TYPE_TEXT_VARIATION_PASSWORD` (text/web/visible) or a
  numeric-password field, and a heuristic skips short all-digit OTP strings.
- Across devices, only **encrypted search snippets** you explicitly request are sent —
  never raw libraries. Frames are AEAD-encrypted with a key derived (HKDF-SHA256) from
  the pairing PSK.

---

## Where AI Hub models plug in

All model calls funnel through `Inference.kt`. To go from stubs to real NPU inference:

1. **Add ONNX Runtime** in `app/build.gradle.kts` (commented integration block):
   ```kotlin
   implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
   ```
   For the **QNN (HTP/NPU) EP** you need a QNN-enabled ORT build (a vendored `.aar` under
   `app/libs`, or the Qualcomm QNN SDK delegate libraries).
2. **Compile models via Qualcomm AI Hub** (`docs/flow-technical-spec.md` §0.4) to QNN
   context binaries / ONNX and place them under `app/src/main/assets/models/`:
   - `all-MiniLM-L6-v2` → text embeddings (384-d) — `embedText`
   - OCR detection + recognition — `ocr`
   - OpenCLIP `ViT-B/32` image + text encoders (512-d) — `embedImage`, `classifyType`
   - `Llama-3.2-3B-Instruct` INT4 (phone LLM) — `generate` (via **Genie / QNN GenAI** or ORT-GenAI)
3. **Build sessions with the chosen EP** inside `Inference.warmUp()` and replace each
   stub body. Switch `preferredEp` between `QNN_NPU` and `CPU` to power the telemetry
   NPU↔CPU toggle (spec §1.0 / §6.3); per-stage latency is already recorded in
   `Inference.lastTimings`.
4. **Swap the vector store**: replace `InMemoryIndex` with an ObjectBox (HNSW) or
   SQLite + `sqlite-vec` implementation of the `Index` interface, using the schema in
   `docs/flow-technical-spec.md` §2.1 so it matches the desktop store.
5. **Real XChaCha20-Poly1305**: `AeadChannel` ships an AES-256-GCM stub (JDK-provided)
   standing in for the spec's XChaCha20-Poly1305. Replace it with Tink's
   `XChaCha20Poly1305` or libsodium (JNI) before interop with the desktop engine; keep
   the `base64(nonce || ciphertext)` framing so only the cipher changes.

---

## Extension points / TODO summary

- `Inference.kt` — every model method (embed/OCR/CLIP/LLM) is a stub.
- `Index.kt` — replace in-memory cosine with ObjectBox or sqlite-vec.
- `Federation.kt` — add reconnect/backoff; add a Ktor embedded server for true mesh;
  replace the AEAD cipher with XChaCha20-Poly1305; stream real files on `FETCH`.
- `TroveIndexer.kt` — small-LLM fallback for poster→event field extraction.
- Sieve (notifications), Relay (clipboard), Threads (commitments) — P1 sensors not yet present.

---

## Tests

```bash
./gradlew testDebugUnitTest
```
Covers: `Item`/`Query`/`Results`/`PairingInfo` JSON round-trips with exact spec field
names; `Config` constants vs `shared/config.json`; in-memory index top-1 retrieval and
dedupe; HKDF-SHA256 determinism; RRF fusion dedupe/ranking.

## License

MIT — see the repository root `LICENSE`.
