// Core UI. Minimal by design — plugins supply the rest. Works fully offline
// from the local mirror; the connection status is always visible so the user
// knows at a glance whether they are seeing synced or local-only data.
"use strict";

function setStatus(online) {
  const el = document.getElementById("status");
  el.textContent = online ? "synced" : "local only";
  el.className = online ? "online" : "offline";
}

async function refreshInbox() {
  try {
    const res = await window.prodos.call({ op: "list", type: "inbox_item" });
    const ul = document.getElementById("inbox");
    ul.textContent = "";
    for (const item of (res.entities || []).slice(0, 20)) {
      const li = document.createElement("li");
      const f = item.fields || {};
      li.textContent = `${f.time || ""} ${f.text || ""}`;
      ul.appendChild(li);
    }
  } catch (e) {
    // offline / bridge not ready — the mirror read still works once ready
  }
}

function wireCapture() {
  document.getElementById("capture-btn").addEventListener("click", async () => {
    const text = prompt("Capture");
    if (!text) return;
    await window.prodos.call({
      op: "upsert", type: "inbox_item",
      data: { text, time: new Date().toTimeString().slice(0, 5) },
    });
    refreshInbox();
  });
}

window.prodos.onReady(() => {
  wireCapture();
  refreshInbox();
  // Status is driven by the native side reporting last-sync success; default
  // to local-only until told otherwise.
  setStatus(false);
});
