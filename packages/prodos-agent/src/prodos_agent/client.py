"""Model client: OpenAI-compatible chat completions against llama.cpp :8080.

The transport is pluggable so the loop can be tested with scripted responses
and so a live model is never required to develop everything around it. A
Client is anything with `.complete(messages) -> str`.
"""

import json
import urllib.request

DEFAULT_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"


class ModelError(Exception):
    pass


class LlamaClient:
    """Talks to a llama.cpp server's OpenAI-compatible endpoint."""

    def __init__(self, endpoint: str = DEFAULT_ENDPOINT, model: str = "local",
                 temperature: float = 0.2, timeout: float = 120.0):
        self.endpoint = endpoint
        self.model = model
        self.temperature = temperature
        self.timeout = timeout

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
            headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                body = json.loads(r.read())
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise ModelError(f"cannot reach model at {self.endpoint}: {e}") from e
        except ValueError as e:
            raise ModelError(f"model returned non-JSON envelope: {e}") from e
        try:
            return body["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as e:
            raise ModelError(f"unexpected completion shape: {e}") from e


class ScriptedClient:
    """Test/dev client: returns queued responses in order.

    Each item is either a literal string or a callable(messages) -> str, so a
    test can react to what the loop actually sent.
    """

    def __init__(self, responses):
        self._responses = list(responses)
        self.calls: list[list[dict]] = []

    def complete(self, messages: list[dict]) -> str:
        self.calls.append(messages)
        if not self._responses:
            raise ModelError("ScriptedClient exhausted")
        nxt = self._responses.pop(0)
        return nxt(messages) if callable(nxt) else nxt
