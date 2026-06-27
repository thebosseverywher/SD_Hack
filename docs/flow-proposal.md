# Flow — Phase 1 Proposal Draft
### Snapdragon Multiverse Hackathon, Bangalore
**Today: June 27, 2026 · Phase 1 proposal due: June 28, 11:59 PM IST (SUBMIT NOW — due tomorrow) · Onsite build: July 11–12**

> **How to use this file:** The "FORM-READY PROPOSAL" section (~600 words) pastes into the submission form. If the form splits into fields (problem / solution / feasibility / impact), lift the matching subsections. Everything after is supporting material. Before submitting, mirror any wording from the "objectives" text on the Hackathon Site.

---

## FORM-READY PROPOSAL

**Title:** Flow — your private, on-device personal intelligence across the phone and laptop you already own

**One-liner:** Flow turns your phone and laptop into one private brain. Each device watches its own slice of your life — your photos, your activity, your files — indexes it on its own NPU, and lets you simply *ask*, from either device: *"Where did I park?"*, *"What do I need for Saturday's trip?"*, *"What did I commit to this week?"* It answers by fusing what every device knows. No cloud, no account, no byte ever leaves your devices.

**The problem.** Your life is scattered across your devices and you can't pull it together when you need it. The serial number is a photo buried in 6,000 others; the invoice is a PDF on your laptop; the thing you promised was something you typed into an app and forgot; the booking confirmation is lost in your notifications. You took the photo *to remember* — and never found it again. Cloud assistants can't fix this, because fixing it means continuously seeing your entire photo library, every app you open, everything you type, and every file you own — and nobody will (or should) upload all of that to a server. A tool that genuinely knows your life **can only exist if it runs entirely on the devices you own.**

**Why your own devices beat the cloud here.** Your phone and laptop sit idle most of the day — tens of TOPS of NPU, already paid for. Cloud AI asks you to pay forever and to upload your most private data. And here is the key: *every capability we add — photos, activity, files, notifications — is data you'd never give the cloud, which is exactly why each one deepens the case for on-device rather than weakening it.* Flow is built for the one user for whom "my devices" structurally beats any datacenter: the everyday person who owns a phone and a laptop and wants the most out of them.

**What it does — one engine, many private sensors.** Flow is a single on-device engine; each feature is another sensor feeding one shared, searchable, fully-local knowledge base:
- **Trove (visual memory):** recognizes the "utility photos" you take to remember things — wifi passwords, parking spots, receipts, serial numbers, posters, whiteboards — extracts the information on-device (OCR + vision) and makes it findable and actionable.
- **Trail (activity & routine):** on the phone an Android AccessibilityService, and on the laptop Windows UI Automation, record what you do — app usage, bookings, text you enter (password and OTP fields excluded by design) — into a private timeline you can ask about.
- **Files (cross-device search):** indexes documents and PDFs on both devices so you can find anything by meaning.

The brain does three things with all of it: **Ask** (natural-language search across every source, from either device), **Surface** (proactive — "you parked at L3-B12 and you're leaving the mall"), and **Act** (drafts the calendar event, sets the reminder). The payoff of many sensors is **cross-source** answers no single tool can give: *"What do I need for Saturday?"* fuses the poster photo, the booking, and the ticket PDF into one reply.

**Symmetric, multi-device by design.** Every device runs the full stack — capture, on-device index, embeddings, and a local LLM — so you can **ask from either device and it answers there.** Retrieval is always federated across both NPUs; only the final answer is composed locally (a larger model on the laptop, a smaller one on the phone — sufficient because answers are retrieval-grounded). A live telemetry panel shows NPU utilization, latency, and tokens/sec on both devices, with an NPU-vs-CPU toggle.

**Committed core (built and hardened before the onsite, polished in the 24 hours):** Android app (APK) + Windows app (EXE/MSIX); on-device indexing of photos (Trove), the activity stream (Trail), and a documents folder (Files) on each device; encrypted local-Wi-Fi sync; federated natural-language query answerable from either device, with source tags; the proactive "Surface" path for at least one scenario; the live telemetry panel.

**Named stretch extensions (optional):** notification intelligence (Sieve), cross-device semantic clipboard (Relay), commitment/plan extraction with proactive reminders (Threads); dynamic routing of answer-generation to the most capable idle NPU; cross-network operation via a synced lightweight index; a screenshot + on-device OCR fallback for apps with empty accessibility trees; audio-conversation capture.

**Feasibility.** The breadth is manageable because it is *one* engine — each feature adds a data type, not a new system — and because we begin building immediately (the rules permit pre-existing work modified with Qualcomm AI Hub models), so the 24 hours onsite is polish and demo, not first-build. Every component is a validated building block: AI Hub models for OCR / embeddings / a small LLM, llama.cpp / ONNX Runtime with the QNN execution provider, sqlite-vec / ObjectBox for the on-device index, a WebSocket over local Wi-Fi with QR-code pairing.

**The demo.** We run Flow on ourselves through the whole build. On stage: (1) a serial number hidden in a phone photo — ask the laptop for the warranty and it answers citing the phone photo *and* the laptop invoice; (2) the cross-source question — "what do I need for the trip?" — answered from three sensors at once; (3) airplane mode on every device, ask again, identical answer.

**Impact.** A private external brain for everyday people whose information and life are split across devices, with a guarantee no cloud product can make: everything stays on hardware you own. A visible indicator shows during capture; the demo uses only the team's own consented data.

---

## Phase 1 rubric mapping (25 pts, 5 each)

| Criterion | Where addressed |
|---|---|
| Multi-device AI applicability | Symmetric peer mesh: ask from either device, federated retrieval across both NPUs, answer composed locally or routed to the best idle NPU. Uses the rubric's own examples — on-device inference, real-time processing, WebSockets. |
| Innovation & creativity | A private, multi-sensor personal intelligence whose cloud versions people actively reject; cross-source answers are the novel payoff of breadth. |
| Feasibility in 24h | One engine, not many apps; pre-building immediately; named, validated components; committed-core vs. stretch split. |
| Potential impact | Everyday "I can't pull my own life together" problem + a privacy guarantee no cloud tool can match. |
| Clarity | One-liner first, sensors/brain structure, plain language. |

**Deviation-proofing:** committed-core vs. named-stretch tiers mean shipping fewer or more features both stay inside the proposal as written (the rules disqualify deviating from the submission without written consent).

## Full feature suite

| Tier | Feature | Captures (private, on-device) | Everyday value |
|---|---|---|---|
| Hero | **Trove** — visual memory | Utility photos: wifi, parking, receipts, serial #s, posters (OCR + vision) | Your camera roll works for you |
| Hero | **Trail** — activity & routine | App use, bookings, typed text (no passwords) — phone (Accessibility) + laptop (UIA) | "What did I do / plan / usually do" |
| Hero | **Files** — cross-device docs | PDFs/docs on both devices | Find anything by meaning |
| Support | **Sieve** — notifications | Phone notification stream | Surface only what matters; "what did I miss" |
| Support | **Relay** — semantic clipboard | Clipboard history across devices | Copy on phone → paste on laptop; poster → event |
| Support | **Threads** — commitments & plans | Promises/dates from any source | "What did I agree to," proactive reminders |
| Stretch | Conversations / Reading / Messages | Real-world audio; what you read; chats | Widest coverage; stated as vision |

## Pre-build plan (June 27 → July 11)

1. **Lock the team (3–5).** Roles: Android dev, Windows/backend dev, ML/models, UX + demo owner. Only the Team Lead submits.
2. **Employer authorization.** Permission is the entrant's responsibility, Qualcomm may verify, and everything submitted is MIT-licensed by rule — clear it with your manager / OSS office before submitting. (For an employed entrant, consider a non-employed teammate as Team Lead / repo owner as a clean fallback.)
3. **Spike the spine first (now):** on any Android phone + any laptop — on-device OCR + image embedding (Trove) → local vector store → WebSocket federated query → a local LLM answers. Target a working "ask laptop, both devices return matches" early.
4. **Add Trail + Files** to the index; validate AccessibilityService / UIA capture with reliable password-field exclusion.
5. **Pre-compile and profile AI Hub models** (OCR, embeddings, small LLM) for Snapdragon 8 Elite and X Elite on AI Hub's device cloud (aihub.qualcomm.com).
6. **Build the telemetry panel early** — it's the 40-pt technical criterion made visible and your debugging tool.
7. **Submit the proposal by June 28** (due tomorrow). No edits after submission. Attend the FAQ session (~early July).

## 24-hour onsite plan (July 11, 1 PM → July 12, 1 PM)

| Hours | Focus |
|---|---|
| 0–4 | Port the pre-built spine to loaner hardware (8 Elite + X Elite); confirm QR-pairing + encrypted sync |
| 4–10 | Harden the three hero sensors (Trove, Trail, Files) on real captured data |
| 8–14 | Federated query + source-tagged fusion + one proactive "Surface" scenario; telemetry panel |
| 14–18 | Add one support feature if on schedule (Sieve or Relay); voice/text query UX |
| 18–22 | Installers (.MSIX + .APK), README + MIT license; rehearse all three demo acts |
| 22–24 | Fallback recording, buffer, final polish |

Start capturing on both devices from hour 0 — the cross-source and routine demos need real data.

## Demo script (5 minutes)

1. (0:30) Problem: "You took the photo to remember it — and never found it again. Your life is scattered across your devices."
2. (1:30) **Trove + Files, ask from either device.** Warranty question answered from the phone photo + laptop invoice, source badges visible. Then ask the same on the phone — same answer, proving symmetry. Telemetry panel: flip NPU→CPU, watch latency jump.
3. (1:30) **Cross-source.** "What do I need for Saturday's trip?" — answered from the poster photo + booking + ticket PDF at once. This is the moment breadth pays off.
4. (0:45) **Privacy.** Airplane mode every device, ask again — identical answer. "No cloud. No account. No server. Your whole life stays yours."
5. (0:45) Stretch demos if shipped (a proactive Surface alert, a support feature); close on impact.

**Anticipated judge question — "Why not Google Photos / ChatGPT?"** Google Photos uploads your entire life, only *finds* photos (doesn't act on them), and is account-locked; ChatGPT has no access to your library and you won't upload it; Apple does some on-device but is Apple-only — the Android+Windows majority has nothing private that does this. And none of them combine your photos, activity, and files into one cross-source answer without ingesting all of it. Flow does, and never uploads a byte.
