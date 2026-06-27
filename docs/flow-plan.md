# Flow — Hackathon Execution Plan
### Snapdragon Multiverse Hackathon, Bangalore · created June 23, 2026

**Objective:** Win an award at the Bangalore hackathon with Flow (private on-device personal intelligence across phone + laptop).
**Primary targets:** Top Award (judges, 100-pt rubric) and Multi-Device Award (best multi-device orchestration — our symmetric mesh + cross-source answers are built for this). Popularization Award (peer vote) is a bonus from a relatable demo. *A team can win only one,* so optimize the demo for the judges' rubric while staying likable for peers.

---

## Timeline at a glance

| Date | Event | Our focus |
|---|---|---|
| **Tue Jun 23 (today)** | — | Kick off all four workstreams |
| Jun 23–27 | Phase 1 window | Finalize + submit proposal; lock team; start employer auth; begin tech spike |
| **Sat Jun 27** | (self-imposed) | **Submit proposal** (1-day buffer) |
| Sun Jun 28 | Phase 1 deadline 11:59 PM IST | (already submitted) |
| Jun 29–30 | Phase 1 judging | Keep building — don't wait |
| **~Jul 1** | Shortlist announced (top ~50) | If in: go to full pre-build |
| Jul 1–10 | Pre-build / harden | Build the prototype to "onsite-ready" |
| early Jul | FAQ session (Teams) | Attend; confirm venue, platforms, hardware |
| **Jul 11, 1 PM → Jul 12, 1 PM** | Phase 2 — 24h onsite build | Port to loaner Snapdragon HW, integrate AI Hub models, harden, polish |
| Jul 12, 1–5 PM | Demos + judging | 3 rehearsed demo acts; submit Final Submission |
| Jul 12, ~5 PM | Winners announced | — |

---

## Four parallel workstreams (all start today)

### A. Proposal (URGENT — 5-day gate)
- [ ] Read the draft in `flow-proposal.md`; edit voice/wording (you)
- [ ] Trim to the actual form fields on the Hackathon Site; mirror its "objectives" language
- [ ] Register on the Hackathon Site; confirm one submission per person, Team Lead submits
- [ ] **Submit by Sat Jun 27** (no edits allowed after submission)

### B. Team + employer authorization (URGENT — gating, can disqualify)
- [ ] Lock 3–5 teammates; confirm everyone's availability for Jul 11–12 *and* pre-build hours
- [ ] Assign roles (below); name the Team Lead (submits + signs loaner agreement)
- [ ] **Every employed member starts employer authorization now.** For you (Marvell): check IP-assignment + moonlighting clauses; get written manager/HR-Legal OK covering (a) permission to participate and (b) Marvell won't claim the IP, so you can MIT-license it. Build only on personal/loaner hardware, personal time.

### C. Technical build (starts today, runs to onsite)
- De-risk hardest-first (see build sequence). The point of starting now: arrive at the onsite with a working prototype so the 24h is porting to Snapdragon, AI Hub integration, NPU optimization, and polish — not first-build.

### D. Demo & pitch (design early, execute late)
- [ ] Decide the 3 hero demo moments now so the build serves them
- [ ] Capture real personal data on dev devices from day 1 (the cross-source + routine demos need it)
- [ ] Pitch deck + 5-min script drafted by Jul 8; rehearse from Jul 10

---

## Milestones (exit criteria)

- **M0 — Jun 27:** Proposal submitted · team locked + roles assigned · employer auth in progress · registered · spike started.
- **M1 — Jul 1 (≈ shortlist):** *Walking skeleton* on dev hardware — ask on laptop, both devices return matches, **Trove** vertical slice works (photo → OCR/embed → index → query → answer). **Riskiest unknown validated: a small LLM runs on a Snapdragon phone NPU** (see risk #1).
- **M2 — Jul 6:** All 3 hero sensors (Trove, Trail, Files) feed one index · federated query works · symmetric (ask from either device) · telemetry panel v1.
- **M3 — Jul 10:** One proactive **Surface** scenario · installers (.APK + .MSIX) · README + MIT license · demo data captured · **full rehearsal #1**. → "Onsite-ready."
- **M4 — Jul 11–12:** Ported to loaner 8 Elite + X Elite · AI Hub models integrated and NPU-optimized *on their hardware* · hardened · one support feature if ahead · 3 rehearsed acts · **Final Submission** (GitHub link via MS Form).

---

## Team & roles (3–5; merge if fewer)

| Role | Owns |
|---|---|
| **Team Lead — Backend & Federation** | WebSocket sync, QR pairing, query orchestration, RAG/brain, the Ask/Surface/Act layer; submits |
| **Android dev** | Trove (camera/photos), Trail (AccessibilityService), Sieve (notifications); on-phone index + small LLM; Android UI |
| **Windows dev** | Trail on laptop (UIA), Windows app, telemetry panel, .MSIX installer, on-PC LLM |
| **ML / models** | AI Hub model compile + profile (OCR, embeddings, LLM); QNN/ONNX integration; **the phone-NPU LLM spike**; optimization for the 40-pt score |
| **UX + Demo owner** | Query UX, demo script, pitch deck, rehearsals, README/docs |

---

## Technical build sequence (de-risk order)

**Architecture recap:** Android app + Windows app, each = capture sensors → on-device index (sqlite-vec/ObjectBox) → embeddings + local LLM → query UI. Shared: WebSocket over local Wi-Fi, QR-code pairing (secret = encryption key). Retrieval federated across both NPUs; answer composed on the asking device. Telemetry panel on both.

1. **Week 1 — kill the scariest risk first:** get a small LLM (1–3B) generating tokens on a Snapdragon phone NPU (Genie/QNN, ONNX Runtime QNN EP, or MLC-LLM) **and** OCR + embeddings running via AI Hub. If the phone LLM won't perform, pivot now to "phone always routes generation to laptop" (still satisfies ask-anywhere).
2. **Federation spine** (hardware-independent): WebSocket + QR pair + query fan-out + result fusion with source tags.
3. **Trove end-to-end** — the first full vertical slice (the M1 skeleton).
4. **Add Trail + Files** into the same index (more sensors, same engine).
5. **Brain modes:** Ask (done) → one **Surface** (proactive) scenario → simple **Act** (draft a calendar event/reminder).
6. **Telemetry panel** (NPU vs CPU toggle, latency, tokens/sec, power) — early, it's also your debugger and worth 40 pts.
7. **Polish:** installers, README, MIT license, demo rehearsal.

**Compliance note:** keep the onsite work substantial and genuine — AI Hub model integration and NPU optimization happen *on the loaner Snapdragon hardware*. That's both within the rules (pre-existing work allowed if modified with AI Hub models) and exactly what the 40-pt "resource utilization, optimization, latency, energy" criterion rewards. Don't show up with a finished cloud-style app and just port it.

---

## Risk register

| # | Risk | Mitigation |
|---|---|---|
| 1 | Small LLM won't run well on 8 Elite NPU | Validate **week 1**; fallback = phone routes generation to laptop when co-located |
| 2 | Accessibility/UIA trees empty; AV flags keylogger | Use foreground-window tracking + screenshot-OCR fallback; avoid raw keyboard hooks in the demo; exclude password fields |
| 3 | RAG gives a bad answer live | Scripted hero queries + retrieval tuning + graceful fallback; don't take open audience questions in the core demo |
| 4 | Loaner hardware setup eats onsite time | Keep the build portable; budget first 4h onsite for porting; test on a similar Snapdragon device beforehand if possible |
| 5 | Marvell (or a teammate's employer) auth blocks participation | Resolve **this week**, in writing, before submitting |
| 6 | Scope creep | Committed core = 3 hero sensors; support/stretch only if ahead; demo stays focused on 2–3 moments |
| 7 | Teammate unavailable Jul 11–12 | Confirm availability now; have a bench/backup |

---

## Final Submission — definition of done (Jul 12, 1 PM)

- [ ] Public GitHub repo, link submitted via the MS Form (provided at Phase 2 start)
- [ ] **No closed-source code**; MIT license file
- [ ] README: app description · all members' names + emails · setup-from-scratch + dependencies · run/usage instructions
- [ ] Runnable per the instructions; installs on the intended Platforms
- [ ] Packaged `.MSIX`/`.EXE` (Windows) and `.APK` (Android)
- [ ] (Recommended) tests + testing instructions, notes, references, well-commented code

---

## This week (Jun 23–27) — do these now
1. Read + finalize the proposal; register; **submit by Jun 27**.
2. Lock the team, assign roles, confirm Jul 11–12 + pre-build availability.
3. Start Marvell authorization (and every employed teammate's) — in writing.
4. Kick the spike: phone-NPU LLM + AI Hub OCR/embeddings (risk #1) + the WebSocket federation skeleton.
