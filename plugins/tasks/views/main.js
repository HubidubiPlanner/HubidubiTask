// Task-list view. Talks to the host over a MessageChannel handed to us via
// postMessage only — no network access, no direct parent access. The bridge
// validates every request against this view's declared permissions.

"use strict";

let port = null;
let nextId = 1;
const pending = new Map();

function call(request) {
  return new Promise((resolve) => {
    const id = nextId++;
    pending.set(id, resolve);
    port.postMessage({ id, ...request });
  });
}

async function refresh() {
  const res = await call({ op: "list", type: "task" });
  const ul = document.getElementById("tasks");
  ul.textContent = "";
  for (const t of res.entities || []) {
    if (t.done) continue;
    const li = document.createElement("li");
    li.textContent = (t.due ? `[${t.due}] ` : "") + t.text + ` (${t.project})`;
    ul.appendChild(li);
  }
}

window.addEventListener("message", (event) => {
  if (event.data && event.data.type === "prodos-bridge" && event.ports[0]) {
    port = event.ports[0];
    port.onmessage = (e) => {
      const resolve = pending.get(e.data.id);
      if (resolve) {
        pending.delete(e.data.id);
        resolve(e.data);
      }
    };
    refresh();
  }
});

document.getElementById("add").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = document.getElementById("text");
  const text = input.value.trim();
  if (!text || !port) return;
  await call({ op: "upsert", type: "task", data: { text, project: "inbox", done: false } });
  input.value = "";
  refresh();
});
