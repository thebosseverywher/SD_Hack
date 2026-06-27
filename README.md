# Flow

**Your private, on-device personal intelligence across the phone and laptop you already own.**

Flow turns your phone and laptop into one private brain. Each device indexes its own slice of your life — photos, activity, files — on its own NPU, and you simply *ask*, from either device: *"Where did I park?"*, *"What do I need for Saturday's trip?"*, *"What did I commit to this week?"* It answers by fusing what every device knows. **No cloud, no account, no byte ever leaves your devices.**

Built for the Snapdragon Multiverse Hackathon (Bangalore, 2026).

> **Status: scaffold / work-in-progress.** The desktop engine runs on CPU out of the box; on-device NPU (Qualcomm QNN) is the optimization target. The Android app is a buildable skeleton. See `docs/` for the full plan and spec.

## Why on-device
Your photos, activity, and files are the most private data you own — the cloud version of this product exists and people reject it. Flow is built for the everyday person whose own (already-paid-for) NPUs structurally beat any datacenter on privacy, cost, and offline availability.

## Architecture — one engine, many private sensors
```
        ┌─────────────────────────────────────────────┐
        │  UI  (ask: voice/text · results · telemetry)  │
        ├─────────────────────────────────────────────┤
        │  Brain  (Ask = RAG · Surface · Act)           │
        ├─────────────────────────────────────────────┤
        │  Index  (sqlite-vec: text 384 + image 512)    │
        ├─────────────────────────────────────────────┤
        │  Inference  (NPU/CPU: OCR · embeddings · LLM)  │
        ├─────────────────────────────────────────────┤
        │  Sensors  (Trove · Trail · Files · …)          │
        └─────────────────────────────────────────────┘
              ▲  federation: WebSocket over local Wi-Fi  ▲
              └───────── QR-paired, AEAD-encrypted ───────┘
```
- **Symmetric peer mesh:** ask from *either* device; retrieval is federated across both NPUs; the answer is composed on the asking device. Only query + small result snippets cross the wire.
- **Sensors (hero):** **Trove** (utility photos — wifi/parking/receipts/serials/posters), **Trail** (activity & routine via Android Accessibility + Windows UI Automation, passwords excluded), **Files** (cross-device document search).
- **Sensors (later):** Sieve (notifications), Relay (clipboard), Threads (commitments).

## Repo layout
```
SD_Hack/
  shared/      # wire protocol + config constants (source of truth)
  desktop/     # Python engine: sensors, index, inference, federation, brain, UI
  android/     # Kotlin app skeleton: sensors, index, inference, federation, UI
  docs/        # plan, technical plan, detailed build spec, this proposal
  LICENSE      # MIT (required by hackathon rules)
```

## Quickstart
- **Desktop engine:** see [`desktop/README.md`](desktop/README.md).
- **Android app:** see [`android/README.md`](android/README.md).
- **Contracts:** [`shared/protocol.md`](shared/protocol.md) and [`shared/config.json`](shared/config.json).

## Docs
- [Proposal](docs/flow-proposal.md) · [Execution plan](docs/flow-plan.md) · [Technical plan](docs/flow-technical-plan.md) · [Detailed build spec](docs/flow-technical-spec.md)

## License
MIT — see [LICENSE](LICENSE).
