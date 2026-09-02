"""Inference endpoint resolution.

Order of precedence:
  1. $PRODOS_MODEL_ENDPOINT (+ $PRODOS_MODEL_API_KEY)
  2. `[inference]` in the vault's .prodos/config.toml
  3. the local selfhostedai-agent OpenAI server (the default)

`[inference]` config shape:

    [inference]
    endpoint = "http://127.0.0.1:8081/v1/chat/completions"
    api_key = ""                 # optional bearer
    tls_fingerprint = ""         # optional sha256:... pin for https endpoints
    model = "local"
"""

import os

from .client import DEFAULT_ENDPOINT, LlamaClient


def resolve(vault) -> dict:
    """Return {endpoint, api_key, tls_fingerprint, model} for the vault."""
    settings = {
        "endpoint": DEFAULT_ENDPOINT,
        "api_key": None,
        "tls_fingerprint": None,
        "model": "local",
    }
    try:
        section = vault.config().get("inference", {})
    except Exception:
        section = {}
    if isinstance(section, dict):
        for key in ("endpoint", "api_key", "tls_fingerprint", "model"):
            if section.get(key):
                settings[key] = section[key]

    env_endpoint = os.environ.get("PRODOS_MODEL_ENDPOINT")
    if env_endpoint:
        settings["endpoint"] = env_endpoint
    env_key = os.environ.get("PRODOS_MODEL_API_KEY")
    if env_key:
        settings["api_key"] = env_key
    return settings


def client_for(vault, **overrides) -> LlamaClient:
    s = {**resolve(vault), **overrides}
    return LlamaClient(endpoint=s["endpoint"], model=s["model"],
                       api_key=s["api_key"], tls_fingerprint=s["tls_fingerprint"])
