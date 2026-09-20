"""The phone must be able to point HubidubiOS at an inference server.

The endpoint lives on the workstation (it holds the URL and the pinned cert),
so without this the only way to set it is hand-editing config.toml on another
machine — which is exactly the gap this closes.
"""

import json
import tomllib
from unittest.mock import MagicMock

import pytest

from hubidubios_sync import server as server_mod


class _Handler(server_mod.Handler):
    """Drive the handlers directly; the HTTP plumbing is stdlib and not ours."""

    def __init__(self, vault, body=None):  # noqa: D107 - no socket setup
        self.server = MagicMock()
        self.server.vault = vault
        self.server.lock = MagicMock(__enter__=lambda s: None, __exit__=lambda *a: False)
        self._test_body = body
        self.sent: tuple[int, dict] | None = None

    def _device(self):
        return "device-1"

    def _body(self):
        return self._test_body

    def _json(self, code, payload):
        self.sent = (code, payload)


@pytest.fixture
def vault(tmp_path):
    from hubidubios_vault.vault import Vault

    v = Vault(tmp_path)
    v.ensure_layout()
    return v


def test_setting_the_endpoint_persists_to_config(vault):
    h = _Handler(vault, {"endpoint": "https://192.168.1.50:8443", "protocol": "selfhostedai"})

    h._set_agent_config()

    assert h.sent[0] == 200, h.sent
    cfg = tomllib.loads(vault.config_path.read_text())["inference"]
    assert cfg["endpoint"] == "https://192.168.1.50:8443"
    assert cfg["protocol"] == "selfhostedai"


def test_a_bad_endpoint_is_refused_rather_than_written(vault):
    h = _Handler(vault, {"endpoint": "192.168.1.50:8443"})   # no scheme

    h._set_agent_config()

    assert h.sent[0] == 400
    assert not vault.config_path.exists(), "an invalid endpoint must not be persisted"


def test_unknown_protocol_is_refused(vault):
    h = _Handler(vault, {"endpoint": "https://h:8443", "protocol": "telepathy"})

    h._set_agent_config()

    assert h.sent[0] == 400


def test_reading_back_never_leaks_the_api_key(vault):
    vault.config_path.parent.mkdir(parents=True, exist_ok=True)
    vault.config_path.write_text(
        '[inference]\nendpoint = "https://h:8443"\napi_key = "sk-secret"\n',
        encoding="utf-8")
    h = _Handler(vault)

    h._get_agent_config()

    payload = h.sent[1]
    assert payload["api_key_set"] is True
    assert "sk-secret" not in json.dumps(payload), "the api key was sent to the device"


def test_saving_invalidates_the_cached_agent(vault):
    """Otherwise the server keeps talking to the old endpoint until restarted."""
    h = _Handler(vault, {"endpoint": "https://new:8443", "protocol": "selfhostedai"})

    h._set_agent_config()

    assert h.server._agent is None and h.server._agent_built is False
