# Core WebView UI

The core mobile UI (HTML/JS/CSS) is authored in
`../app/src/main/assets/web/` so `WebViewAssetLoader` serves it at an
`https://appassets.androidplatform.net/assets/web/` origin — a secure context,
required for the `WebMessagePort` bridge and web crypto.

Files:

- `index.html` — home: capture button, plugin-view launcher, recent captures,
  always-visible connection status.
- `host.js` — the native↔web bridge bootstrap. Waits for the native shell to
  hand over a `WebMessagePort`, exposes `window.prodos.call()`, and brokers
  per-view `MessageChannel`s for plugin iframes.
- `app.js` — the core UI logic (offline-first reads from the mirror).
- `app.css` — styling, light/dark aware.

Plugin views are fetched and cached by the sync layer, verified by signature,
and served from internal storage under `/plugins/<name>/…` — each in its own
origin-isolated iframe, permission-checked by the native `WebBridge` exactly as
on the desktop host. No plugin ever loads code over the network at runtime.
