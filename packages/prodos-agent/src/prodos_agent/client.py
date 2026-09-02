"""Model client: OpenAI-compatible chat completions.

The transport is pluggable so the loop can be tested with scripted responses
and so a live model is never required to develop everything around it. A
Client is anything with `.complete(messages) -> str`.

Default target is the user's local selfhostedai-agent OpenAI server — plaintext,
loopback-only, optional bearer key (`the server's OpenAI API code`
binds 127.0.0.1:8081 and refuses non-loopback). "OpenAI-compatible is a wire
format, not a destination": any such endpoint (selfhostedai-agent, llama.cpp, vLLM)
works by pointing `endpoint` at its `/v1`.
"""

import json
import ssl
import urllib.request

# selfhostedai-agent's OpenAI-compatible server (local-only). llama.cpp on :8080 is a
# documented fallback; both speak the same wire format.
SELFHOSTEDAI_BASE = "http://127.0.0.1:8081/v1"
DEFAULT_ENDPOINT = SELFHOSTEDAI_BASE + "/chat/completions"


class ModelError(Exception):
    pass


def _base_of(endpoint: str) -> str:
    """The `/v1` base URL for an endpoint that may point at .../chat/completions."""
    if endpoint.endswith("/chat/completions"):
        return endpoint[: -len("/chat/completions")]
    return endpoint.rstrip("/")


class LlamaClient:
    """Talks to an OpenAI-compatible endpoint (selfhostedai-agent by default).

    `tls_fingerprint` pins a self-signed cert (sha256:...) for non-loopback
    https endpoints; unset, plain http/https via the system trust store is used.
    The local selfhostedai default is plaintext loopback and needs neither.
    """

    def __init__(self, endpoint: str = DEFAULT_ENDPOINT, model: str = "local",
                 temperature: float = 0.2, timeout: float = 120.0,
                 api_key: str | None = None, tls_fingerprint: str | None = None):
        self.endpoint = endpoint
        self.base = _base_of(endpoint)
        self.model = model
        self.temperature = temperature
        self.timeout = timeout
        self.api_key = api_key or None
        self.tls_fingerprint = tls_fingerprint or None

    # ------------------------------------------------------------ transport

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.api_key:
            h["Authorization"] = f"Bearer {self.api_key}"
        return h

    def _ssl_context(self):
        if not self.tls_fingerprint:
            return None
        # Pin the exact leaf cert by fingerprint (loopback/self-signed servers).
        expected = self.tls_fingerprint.removeprefix("sha256:").lower()
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

        import hashlib

        orig = ctx.wrap_socket

        def wrap(sock, *a, **k):
            ssock = orig(sock, *a, **k)
            der = ssock.getpeercert(binary_form=True)
            got = hashlib.sha256(der).hexdigest()
            if got != expected:
                ssock.close()
                raise ModelError("model endpoint cert fingerprint mismatch")
            return ssock

        ctx.wrap_socket = wrap  # type: ignore[method-assign]
        return ctx

    def _open(self, req):
        ctx = self._ssl_context()
        return urllib.request.urlopen(req, timeout=self.timeout, context=ctx)

    # ------------------------------------------------------------ requests

    def complete(self, messages: list[dict]) -> str:
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": self.temperature,
            "stream": False,
            # Nudge the server toward JSON where it supports it; the loop
            # tolerates prose-wrapped JSON regardless.
            "response_format": {"type": "json_object"},
        }
        req = urllib.request.Request(
            self.endpoint, data=json.dumps(payload).encode("utf-8"),
            headers=self._headers(), method="POST")
        try:
            with self._open(req) as r:
                body = json.loads(r.read())
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise ModelError(f"cannot reach model at {self.endpoint}: {e}") from e
        except ValueError as e:
            raise ModelError(f"model returned non-JSON envelope: {e}") from e
        try:
            return body["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as e:
            raise ModelError(f"unexpected completion shape: {e}") from e

    def health(self) -> dict:
        """Probe `GET /v1/models`. The single source of truth for the
        connection-status indicator across CLI, desktop, and mobile.
        Never raises — returns {connected, server, models, error?}."""
        url = self.base + "/models"
        req = urllib.request.Request(url, headers=self._headers(), method="GET")
        try:
            with self._open(req) as r:
                body = json.loads(r.read())
        except Exception as e:  # unreachable / bad response — report, don't raise
            return {"connected": False, "server": self.base, "models": [],
                    "error": str(e)}
        models = []
        try:
            models = [m.get("id") for m in body.get("data", []) if m.get("id")]
        except (AttributeError, TypeError):
            pass
        return {"connected": True, "server": self.base, "models": models}


class ScriptedClient:
    """Test/dev client: returns queued responses in order.

    Each item is either a literal string or a callable(messages) -> str, so a
    test can react to what the loop actually sent.
    """

    def __init__(self, responses, health: dict | None = None):
        self._responses = list(responses)
        self.calls: list[list[dict]] = []
        self._health = health or {"connected": True, "server": "scripted",
                                  "models": ["scripted"]}

    def complete(self, messages: list[dict]) -> str:
        self.calls.append(messages)
        if not self._responses:
            raise ModelError("ScriptedClient exhausted")
        nxt = self._responses.pop(0)
        return nxt(messages) if callable(nxt) else nxt

    def health(self) -> dict:
        return self._health
