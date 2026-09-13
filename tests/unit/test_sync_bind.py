"""`serve --bind auto` must never silently bind loopback.

The sync server runs as a user service, so it can start before the network has
an address. `_lan_ip()` falls back to 127.0.0.1 on failure and the bind address
is resolved once at startup — so without the wait below the server ends up on
loopback permanently, looking healthy while the phone's connections time out.
"""

import hubidubios_sync.cli as cli


def test_returns_address_once_the_network_comes_up(monkeypatch):
    """Loopback early on must not be taken as the answer."""
    answers = iter(["127.0.0.1", "127.0.0.1", "192.168.1.61"])
    monkeypatch.setattr(cli, "_lan_ip", lambda: next(answers))

    assert cli._wait_for_lan_ip(timeout=10, _sleep=0) == "192.168.1.61"


def test_gives_up_rather_than_returning_loopback(monkeypatch):
    """A network that never appears must fail, not bind where nothing can reach.

    Returning None makes cmd_serve exit non-zero so Restart=on-failure retries.
    """
    monkeypatch.setattr(cli, "_lan_ip", lambda: "127.0.0.1")

    assert cli._wait_for_lan_ip(timeout=0, _sleep=0) is None


def test_takes_the_address_immediately_when_already_up(monkeypatch):
    calls = []

    def fake_lan_ip():
        calls.append(1)
        return "10.0.0.5"

    monkeypatch.setattr(cli, "_lan_ip", fake_lan_ip)

    assert cli._wait_for_lan_ip(timeout=10, _sleep=0) == "10.0.0.5"
    assert len(calls) == 1, "should not poll again once an address exists"
