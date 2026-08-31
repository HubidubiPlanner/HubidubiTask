"""View hosting primitives: origin isolation and CSP.

A plugin's view JS must never run in the host page's origin — it would read
the entire vault regardless of declared permissions. Each view iframe is
sandboxed WITHOUT allow-same-origin, giving it an opaque (distinct) origin;
communication is postMessage over a MessageChannel only.

The CSP forbids remote script, remote style, and remote fetch: views load
only from the installed bundle. No plugin ever loads code over the network
at runtime.

These primitives are shared by whatever shell embeds them (browser page on
the desktop, WebView on Android in Phase 5); rendering enforcement is the
browser's job and is exercised in a real engine on target.
"""

from html import escape

IFRAME_SANDBOX = "allow-scripts"  # deliberately NOT allow-same-origin

VIEW_CSP = (
    "default-src 'none'; "
    "script-src 'self'; "
    "style-src 'self'; "
    "img-src 'self' data:; "
    "connect-src 'none'; "
    "frame-ancestors *"
)

HOST_CSP = (
    "default-src 'none'; "
    "script-src 'self'; "
    "style-src 'self'; "
    "img-src 'self' data:; "
    "connect-src 'none'; "
    "frame-src 'self'"
)


def iframe_html(plugin_name: str, view: dict) -> str:
    src = f"/plugins/{escape(plugin_name)}/views/index.html#{escape(view['id'])}"
    return (f'<iframe sandbox="{IFRAME_SANDBOX}" '
            f'src="{src}" title="{escape(view.get("title", view["id"]))}" '
            f'data-plugin="{escape(plugin_name)}" data-view="{escape(view["id"])}">'
            f'</iframe>')


def host_page(registry) -> str:
    frames = []
    for plugin in registry.plugins.values():
        for view in plugin.manifest.get("views", []):
            frames.append(iframe_html(plugin.name, view))
    return (
        "<!doctype html>\n<html><head>"
        f'<meta http-equiv="Content-Security-Policy" content="{HOST_CSP}">'
        '<meta charset="utf-8"><title>prodos</title>'
        '<script src="/host.js"></script>'
        "</head><body>\n" + "\n".join(frames) + "\n</body></html>\n"
    )
