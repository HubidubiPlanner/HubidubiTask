// GTD views. Talks to the host over a MessageChannel only — no network, no
// direct parent access. The bridge permission-checks every request against
// this view's declared permissions.
//
// The four views share one bundle, selected by URL hash. All render offline
// from the local mirror; clarify's AI proposal is the one online-only feature.
"use strict";

let port = null, nextId = 1;
const pending = new Map();

function call(request) {
  return new Promise((resolve) => {
    const id = nextId++;
    pending.set(id, resolve);
    port.postMessage({ id, ...request });
  });
}

const root = () => document.getElementById("root");
const el = (tag, props = {}, kids = []) => {
  const n = document.createElement(tag);
  Object.assign(n, props);
  for (const k of [].concat(kids)) n.append(k);
  return n;
};

async function renderCapture() {
  const r = root(); r.textContent = "";
  const input = el("input", { placeholder: "Capture…" });
  const btn = el("button", { className: "primary", textContent: "Capture" });
  btn.onclick = async () => {
    const text = input.value.trim();
    if (!text) return;
    await call({ op: "upsert", type: "inbox_item", data: { text } });
    input.value = "";
    input.focus();
  };
  r.append(el("h1", { textContent: "Capture" }), input, btn);
  input.focus();
}

async function renderNext() {
  const r = root(); r.textContent = "";
  const res = await call({ op: "list", type: "next_action" });
  const ul = el("ul");
  for (const a of (res.entities || [])) {
    const f = a.fields || {};
    if (f.done) continue;
    ul.append(el("li", { textContent:
      `${f.context || ""} ${f.text || ""}${f.due ? " · due " + f.due : ""}` }));
  }
  r.append(el("h1", { textContent: "Next actions" }), ul);
}

async function renderClarify() {
  const r = root(); r.textContent = "";
  const res = await call({ op: "list", type: "inbox_item" });
  const item = (res.entities || [])[0];
  if (!item) { r.append(el("h1", { textContent: "Inbox empty 🎉" })); return; }
  const f = item.fields || {};
  r.append(el("h1", { textContent: "Clarify" }), el("p", { textContent: f.text }));

  // Editable proposal form (AI pre-fill arrives via the host's agent bridge;
  // fields are always user-editable — clarify proposes, the user commits).
  const kind = el("select");
  ["next_action", "project", "waiting_for", "someday", "trash"].forEach((k) =>
    kind.append(el("option", { value: k, textContent: k })));
  const context = el("input", { placeholder: "@context" });
  const commit = el("button", { className: "primary", textContent: "File it" });
  commit.onclick = async () => {
    const type = kind.value;
    if (type !== "trash") {
      const data = { text: f.text };
      if (context.value) data.context = context.value;
      await call({ op: "upsert", type, data });
    }
    await call({ op: "delete", type: "inbox_item", id: item.id });
    renderClarify();
  };
  r.append(el("div", { className: "field" }, [el("label", { textContent: "Kind" }), kind]),
           el("div", { className: "field" }, [el("label", { textContent: "Context" }), context]),
           commit);
}

async function renderReview() {
  const r = root(); r.textContent = "";
  r.append(el("h1", { textContent: "Weekly review" }));
  const projects = await call({ op: "list", type: "project" });
  const actions = await call({ op: "list", type: "next_action" });
  const haveAction = new Set(
    (actions.entities || []).filter((a) => !a.fields.done).map((a) => a.fields.project));
  const stale = (projects.entities || [])
    .filter((p) => !haveAction.has((p.fields.title || "").toLowerCase().replace(/\s+/g, "-")));
  r.append(el("h2", { textContent: "Projects with no next action" }));
  const ul = el("ul");
  if (stale.length === 0) ul.append(el("li", { textContent: "none — good" }));
  for (const p of stale) ul.append(el("li", { className: "alert", textContent: p.fields.title }));
  r.append(ul);
}

const VIEWS = {
  "capture": renderCapture, "next-actions": renderNext,
  "clarify": renderClarify, "weekly-review": renderReview,
};

window.addEventListener("message", (event) => {
  if (event.data && event.data.type === "prodos-bridge" && event.ports[0]) {
    port = event.ports[0];
    port.onmessage = (e) => {
      const resolve = pending.get(e.data.id);
      if (resolve) { pending.delete(e.data.id); resolve(e.data); }
    };
    const view = (location.hash.slice(1) || "capture");
    (VIEWS[view] || renderCapture)();
  }
});
