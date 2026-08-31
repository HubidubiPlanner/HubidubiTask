// Native <-> web bridge bootstrap.
//
// The native shell establishes a WebMessagePort with the page (not
// addJavascriptInterface). We wait for the native side to post the primary
// port, then expose window.prodos.call() to app.js and broker per-view
// MessageChannels for plugin iframes — each of which the native WebBridge
// permission-checks independently.
"use strict";

(function () {
  let nativePort = null;
  const pending = new Map();
  let nextId = 1;
  const ready = [];

  window.prodos = {
    call(request) {
      return new Promise((resolve, reject) => {
        if (!nativePort) return reject(new Error("bridge not ready"));
        const id = nextId++;
        pending.set(id, resolve);
        nativePort.postMessage(JSON.stringify({ id, ...request }));
      });
    },
    onReady(fn) {
      if (nativePort) fn(); else ready.push(fn);
    },
  };

  // The native side calls webView.postWebMessage(port) with a transferable
  // MessagePort; it arrives as a message on window whose ports[0] is ours.
  window.addEventListener("message", (event) => {
    if (nativePort || !event.ports || !event.ports[0]) return;
    nativePort = event.ports[0];
    nativePort.onmessage = (e) => {
      const msg = typeof e.data === "string" ? JSON.parse(e.data) : e.data;
      const resolve = pending.get(msg.id);
      if (resolve) { pending.delete(msg.id); resolve(msg); }
    };
    ready.splice(0).forEach((fn) => fn());
  });
})();
