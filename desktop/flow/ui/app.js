// Flow web UI — query box, results with source/device badges + thumbnails,
// and a live telemetry panel with an NPU/CPU toggle wired to /telemetry/ep.

const $ = (sel) => document.querySelector(sel);

async function api(path, opts) {
  const res = await fetch(path, opts);
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json();
}

// ---- Ask ----
$("#ask-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const text = $("#query").value.trim();
  if (!text) return;
  const answer = $("#answer");
  answer.classList.remove("hidden");
  answer.textContent = "Thinking…";
  $("#results").innerHTML = "";
  try {
    const data = await api("/ask", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    });
    answer.textContent = data.answer || "(no answer)";
    renderResults(data.hits || []);
    renderTelemetry(data.telemetry);
  } catch (err) {
    answer.textContent = "Error: " + err.message;
  }
});

function renderResults(hits) {
  const ul = $("#results");
  ul.innerHTML = "";
  for (const h of hits) {
    const li = document.createElement("li");
    li.className = "result";
    const thumb = h.thumb_b64
      ? `<img src="data:image/jpeg;base64,${h.thumb_b64}" alt="thumb" />`
      : "";
    const fields = h.fields && Object.keys(h.fields).length
      ? `<div class="snippet">${Object.entries(h.fields)
          .map(([k, v]) => `<b>${k}</b>: ${escapeHtml(String(v))}`).join(" · ")}</div>`
      : "";
    li.innerHTML = `
      ${thumb}
      <div style="flex:1">
        <div class="meta">
          <span class="badge device">${escapeHtml(h.device_id || "?")}</span>
          <span class="badge source">${escapeHtml(h.source || "?")}</span>
          <span class="badge">${escapeHtml(h.type || "other")}</span>
          <span class="score">score ${(+h.score).toFixed(3)}</span>
        </div>
        <div class="snippet">${escapeHtml((h.text || "").slice(0, 220))}</div>
        ${fields}
      </div>`;
    ul.appendChild(li);
  }
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ---- Telemetry ----
function renderTelemetry(t) {
  if (!t) return;
  const tbody = $("#stages tbody");
  tbody.innerHTML = "";
  const stages = t.stages || {};
  for (const name of Object.keys(stages)) {
    const s = stages[name];
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${escapeHtml(name)}</td><td>${escapeHtml(s.ep)}</td>` +
      `<td class="ms">${(+s.ms).toFixed(1)}</td>` +
      `<td>${s.tokens_s ? (+s.tokens_s).toFixed(1) : "—"}</td>`;
    tbody.appendChild(tr);
  }
  // reflect active EP on the toggle
  for (const btn of document.querySelectorAll(".ep")) {
    btn.classList.toggle("active", btn.dataset.ep === t.active_ep);
  }
}

// EP toggle (NPU <-> CPU). On a CPU-only machine, QNN honestly reports fallback.
for (const btn of document.querySelectorAll(".ep")) {
  btn.addEventListener("click", async () => {
    const ep = btn.dataset.ep;
    try {
      const r = await api("/telemetry/ep", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ep }),
      });
      $("#ep-note").textContent = r.note || `Active EP: ${r.active_ep}`;
      for (const b of document.querySelectorAll(".ep"))
        b.classList.toggle("active", b.dataset.ep === r.active_ep);
    } catch (err) {
      $("#ep-note").textContent = "EP switch failed: " + err.message;
    }
  });
}

// ---- Health + periodic telemetry/peers refresh ----
async function refresh() {
  try {
    const h = await api("/healthz");
    $("#health").textContent =
      `${h.items} items · ${h.vec_backend} · text:${h.text_embed ? "on" : "off"} ` +
      `· image:${h.image_embed ? "on" : "off"} · QNN:${h.qnn_available ? "yes" : "no"}`;
  } catch {}
  try {
    const t = await api("/telemetry");
    renderTelemetry(t);
  } catch {}
  try {
    const p = await api("/peers");
    $("#peers").textContent = `peers: ${(p.peers || []).length}`;
  } catch {}
}
refresh();
setInterval(refresh, 4000);
