# Flow — Desktop Engine (`flow`)

On-device, federated personal-memory search for the Snapdragon Multiverse
Hackathon. This is the **desktop** half of Flow: a cohesive Python 3.11 package
that indexes your documents, photos and activity, answers questions over them
with citations, and federates queries to paired devices (e.g. the Android app)
over an encrypted LAN channel.

**It runs entirely on CPU out of the box** — no NPU, no GPU, and no mandatory
model downloads beyond the small MiniLM text embedder (auto-fetched on first
use by `sentence-transformers`). On-device **NPU acceleration via ONNX Runtime's
QNN execution provider** is the documented optimization target, wired through
`flow/inference.py` with clear extension points (`# TODO(QNN)`), not a hard
dependency.

---

## What's inside

| Module | Role |
|---|---|
| `flow/config.py` | Loads `../shared/config.json` (dims, port, enums, model names). |
| `flow/protocol.py` | Dataclasses for `Item`, `Hit`, and every wire message (HELLO/QUERY/RESULTS/FETCH/FETCH_RESULT/SURFACE). Field names match `shared/protocol.md` exactly. |
| `flow/inference.py` | ONNX Runtime EP-switching abstraction (QNN ↔ CPU) powering the telemetry toggle; falls back to CPU automatically. |
| `flow/embeddings.py` | Text (384-d MiniLM, CPU) + image (512-d CLIP, optional) embeddings. |
| `flow/index.py` | SQLite + `sqlite-vec` store (items + `vec_text[384]` + `vec_image[512]`); **numpy brute-force fallback** if the extension can't load. Dedupe by content hash; never stores password-flagged text. |
| `flow/crypto.py` | HKDF-SHA256 key + XChaCha20-Poly1305 seal/open; per-message nonce; replay rejection. |
| `flow/federation.py` | WebSocket server+client peer mesh; QR pairing; QUERY fan-out with timeout; FETCH. |
| `flow/fusion.py` | Reciprocal Rank Fusion across the text + image spaces; dedupe; device tagging. |
| `flow/llm.py` | Pluggable LLM: `llama-cpp-python` if a GGUF model is configured, else an **extractive fallback** so the engine always answers. |
| `flow/brain.py` | Ask (federated RAG), Surface (rule engine + parking demo), Act (OS-intent stub). |
| `flow/sensors/` | `files` (docs), `trove` (images + optional OCR/CLIP), `trail_win` (Windows activity timeline). |
| `flow/telemetry.py` | Per-stage `{stage, ep, ms, tokens_s}` events for the UI panel. |
| `flow/server.py` | FastAPI app: web UI, `/ask`, `/pair`, telemetry, federation WS. |
| `flow/cli.py` | `flow serve | index | ask | pair | trail`. |
| `flow/ui/` | Minimal web UI: query box, results with device/source badges + thumbnails, telemetry panel with NPU/CPU toggle. |

---

## Setup from scratch

Requires **Python 3.11**.

```bash
cd desktop

# 1) Create and activate a virtual environment
python -m venv .venv
# Windows (PowerShell):
.venv\Scripts\Activate.ps1
# Windows (Git Bash) / macOS / Linux:
source .venv/Scripts/activate    # or .venv/bin/activate on macOS/Linux

# 2) Install the core (CPU) dependencies
pip install -r requirements.txt
#   …or, as an installable package with the console script:
pip install -e .
```

That's everything needed to run. The first text query downloads the small
`all-MiniLM-L6-v2` model (~90 MB) via `sentence-transformers`. If even that is
unavailable, embeddings degrade to zero-vectors and the engine still starts
(retrieval just returns nothing) — nothing crashes.

### Optional extras

Install only what you want; each is independent and degrades gracefully:

```bash
pip install -e ".[image]"   # open-clip-torch  → 512-d image search + zero-shot typing
pip install -e ".[llm]"     # llama-cpp-python → local GGUF generation (else extractive)
pip install -e ".[ocr]"     # easyocr          → OCR text from Trove photos
pip install -e ".[win]"     # pywin32          → Windows Trail activity timeline
pip install -e ".[dev]"     # pytest           → tests
pip install -e ".[all]"     # everything above
```

For a local generative LLM, point Flow at a GGUF file:

```bash
export FLOW_LLM_MODEL=/path/to/Llama-3.1-8B-Instruct.Q4_K_M.gguf   # PowerShell: $env:FLOW_LLM_MODEL=...
```

---

## Running

```bash
# Start the web UI + federation server (UI at http://127.0.0.1:8000)
flow serve
#   or without installing:  python -m flow serve

# Index a folder of documents (PDF / DOCX / TXT / MD)
flow index "C:/Users/me/Documents"

# Index a folder of photos (OCR + CLIP if installed)
flow index "C:/Users/me/Pictures" --trove

# Ask a question against the local index
flow ask "what's the office wifi password"

# Produce the pairing QR (writes flow-pair.png) for the Android app to scan
flow pair

# Windows activity timeline (needs the [win] extra)
flow trail --duration 60
```

### Environment knobs

| Variable | Meaning | Default |
|---|---|---|
| `FLOW_DB` | SQLite DB path | `~/.flow/flow.db` |
| `FLOW_DEVICE_ID` / `FLOW_DEVICE_NAME` | this node's identity | host-derived |
| `FLOW_LLM_MODEL` | GGUF model path for `llama.cpp` | _(unset → extractive)_ |
| `FLOW_QNN_BACKEND` | path to the QNN HTP backend lib | _(unset → CPU only)_ |
| `FLOW_CONFIG` | override `shared/config.json` location | auto-discovered |

---

## How the NPU / QNN path plugs in

The whole inference surface goes through `flow/inference.py`:

```python
from flow.inference import OnnxModel, EP_QNN, EP_CPU
model = OnnxModel.load("minilm.onnx", ep=EP_QNN)   # falls back to CPU if QNN absent
outputs, latency_ms = model.run(feeds)
```

* `OnnxModel.load(path, ep)` builds an ONNX Runtime session with either
  `QNNExecutionProvider` (NPU/HTP) or `CPUExecutionProvider`. If QNN is
  requested but unavailable, it **transparently falls back to CPU** and records
  an honest `ep_fallback` telemetry event — the UI always shows the EP that
  actually ran.
* The web UI's **NPU ↔ CPU toggle** (`POST /telemetry/ep`) flips
  `telemetry.active_ep`. On a CPU-only laptop this is CPU-vs-CPU today, with the
  exact hook (`benchmark()` + `OnnxModel.load(..., ep=EP_QNN)`) ready to show
  the real latency delta once the QNN EP + HTP backend are installed.

**To enable the NPU on a Snapdragon X Elite machine:**

1. `pip install -e ".[npu]"` (ONNX Runtime QNN build) and install the Qualcomm
   AI Engine Direct (QNN) SDK + HTP backend libraries.
2. Set `FLOW_QNN_BACKEND` to the HTP backend `.dll`/`.so`.
3. Export the models to ONNX (MiniLM, CLIP) or compile INT4 via **Qualcomm AI
   Hub**; the `# TODO(QNN)` markers in `inference.py` and `llm.py` show where the
   QNN-compiled embedder and the **Genie (QNN GenAI)** LLM session attach. The
   `generate()` signature is stable, so `brain.py` needs no changes.

---

## Tests

```bash
pip install -e ".[dev]"
pytest            # from the desktop/ directory
```

Covers: protocol round-trips, index ingest + search (with dedupe and secret
rejection), crypto seal/open + tamper + replay rejection, RRF fusion ordering,
and an **end-to-end smoke test** that indexes a temp `.txt` and asks a question
using the extractive LLM fallback. The suite is hermetic — it falls back to a
tiny deterministic embedder if the MiniLM model isn't downloaded — so it runs on
a bare machine with no network.

---

## Notes & assumptions

* **Wire compatibility:** message/field names mirror `shared/protocol.md`
  exactly so this engine and the Android app interoperate. Major-version
  mismatches refuse to decode.
* **Two embedding spaces are never mixed** (text 384 vs image 512); they are
  searched separately and fused with RRF.
* **Privacy:** items flagged `is_password`/`is_secret` are never persisted;
  Windows Trail skips obvious login/OTP window titles; nodes answer queries from
  their own index only and return snippets/thumbnails, never raw libraries.
* **Graceful degradation everywhere:** missing `sqlite-vec`, CLIP, OCR,
  `llama.cpp`, `pywin32`, or even the embedding model never crash the core path.

MIT-licensed — see the repository root `LICENSE`.
