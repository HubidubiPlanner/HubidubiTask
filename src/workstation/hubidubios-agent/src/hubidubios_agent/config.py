"""Inference endpoint resolution.

Order of precedence:
  1. $HUBIDUBIOS_MODEL_ENDPOINT (+ $HUBIDUBIOS_MODEL_API_KEY)
  2. `[inference]` in the vault's .hubidubios/config.toml
  3. the local selfhostedai-agent OpenAI server (the default)

`[inference]` config shape:

    [inference]
    endpoint = "http://127.0.0.1:8081/v1/chat/completions"
    api_key = ""                 # optional bearer
    tls_fingerprint = ""         # optional sha256:... pin for https endpoints
    model = "local"
    protocol = "openai"          # or "selfhostedai" for the encrypted :8443 API
    cot = "assistant"            # selfhostedai only: which chain of thought to run
    language = "auto"            # "auto" matches the material; "en"/"pl" force one
    no_think = true              # selfhostedai only: suppress the reasoning monologue
    use_cache = false            # selfhostedai only: replay a cached answer for the
                                 # same prompt instead of asking the model

To use a selfhostedai-agent over the LAN (its OpenAI server is loopback-only, so the
reachable surface is the encrypted :8443 API):

    [inference]
    protocol = "selfhostedai"
    endpoint = "https://192.168.1.50:8443"
    model = "fast"
    cot = "assistant"
"""

import os

from .client import DEFAULT_ENDPOINT, LlamaClient


def resolve(vault) -> dict:
    """Return {endpoint, api_key, tls_fingerprint, model, protocol} for the vault."""
    settings = {
        "endpoint": DEFAULT_ENDPOINT,
        "api_key": None,
        "tls_fingerprint": None,
        "model": "local",
        "protocol": "openai",
        "cot": "assistant",
        "language": "auto",
        "no_think": True,
        "use_cache": False,
    }
    try:
        section = vault.config().get("inference", {})
    except Exception:
        section = {}
    if isinstance(section, dict):
        for key in ("endpoint", "api_key", "tls_fingerprint", "model",
                    "protocol", "cot", "language"):
            if section.get(key):
                settings[key] = section[key]
        for flag in ("no_think", "use_cache"):
            if flag in section:
                settings[flag] = bool(section[flag])

    env_endpoint = os.environ.get("HUBIDUBIOS_MODEL_ENDPOINT")
    if env_endpoint:
        settings["endpoint"] = env_endpoint
    env_key = os.environ.get("HUBIDUBIOS_MODEL_API_KEY")
    if env_key:
        settings["api_key"] = env_key
    env_proto = os.environ.get("HUBIDUBIOS_MODEL_PROTOCOL")
    if env_proto:
        settings["protocol"] = env_proto
    return settings


# Which language the assistant answers in. The model will otherwise pick one
# per reply — English here, Polish there — from whatever the note happens to be
# written in, and a vault that answers inconsistently is worse than one that
# answers in the language you did not pick.
_LANGUAGE_INSTRUCTIONS = {
    "au": "Answer in the same language as the material.",
    "en": "Always answer in English, whatever language the material is in.",
    "pl": "Zawsze odpowiadaj po polsku, niezaleznie od jezyka materialu.",
}


def language_instruction(vault=None, language: str | None = None) -> str:
    """The sentence that pins the reply language, for any prompt asking for prose.

    "auto" is the default because it is the rule that does not fight the
    material. Measured against the live server: demanding English of a Polish
    note (or the reverse) sent the model into 30-41s of reasoning that ended
    with no answer at all, while matching the material answered in seconds.
    Forcing one language is still available — it just costs that sometimes.
    """
    lang = (language or (resolve(vault).get("language") if vault else None) or "auto")
    return _LANGUAGE_INSTRUCTIONS.get(str(lang).lower()[:2], _LANGUAGE_INSTRUCTIONS["au"])


def client_for(vault, **overrides):
    """Build the model client for the vault. `protocol` selects the transport:
    the default OpenAI-compatible LlamaClient, or the encrypted SelfhostedaiClient."""
    s = {**resolve(vault), **overrides}
    if str(s.get("protocol")).lower() == "selfhostedai":
        from .selfhostedai import SelfhostedaiClient
        return SelfhostedaiClient(endpoint=s["endpoint"], model=s.get("model") or "fast",
                             tls_fingerprint=s["tls_fingerprint"],
                             cot_id=s.get("cot") or "assistant",
                             no_think=bool(s.get("no_think", True)),
                             use_cache=bool(s.get("use_cache", False)))
    return LlamaClient(endpoint=s["endpoint"], model=s["model"],
                       api_key=s["api_key"], tls_fingerprint=s["tls_fingerprint"])
