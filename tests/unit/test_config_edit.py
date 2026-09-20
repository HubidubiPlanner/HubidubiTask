"""Editing one config section must not disturb the rest of the file.

config.toml is hand-edited as often as it is machine-set, so the phone writing
an inference endpoint must not reformat the file or drop the user's comments.
"""

import tomllib

from hubidubios_vault.config_edit import set_section


def test_creates_the_file_when_absent(tmp_path):
    p = tmp_path / "config.toml"

    set_section(p, "inference", {"protocol": "selfhostedai", "endpoint": "https://h:8443"})

    assert tomllib.loads(p.read_text())["inference"]["endpoint"] == "https://h:8443"


def test_other_sections_and_comments_survive(tmp_path):
    p = tmp_path / "config.toml"
    p.write_text(
        "# my notes about this file\n"
        "[calendar]\n"
        'google_ics_urls = ["https://example/basic.ics"]\n'
        "\n"
        "[inference]\n"
        'endpoint = "http://old:8081"\n',
        encoding="utf-8")

    set_section(p, "inference", {"protocol": "selfhostedai", "endpoint": "https://new:8443"})

    text = p.read_text()
    assert "# my notes about this file" in text, "a user comment was destroyed"
    cfg = tomllib.loads(text)
    assert cfg["calendar"]["google_ics_urls"] == ["https://example/basic.ics"]
    assert cfg["inference"] == {"protocol": "selfhostedai", "endpoint": "https://new:8443"}


def test_replacing_drops_keys_no_longer_set(tmp_path):
    """A setting cleared in the UI must actually be cleared on disk."""
    p = tmp_path / "config.toml"
    p.write_text('[inference]\nendpoint = "x"\ntls_fingerprint = "sha256:old"\n',
                 encoding="utf-8")

    set_section(p, "inference", {"endpoint": "y"})

    assert "tls_fingerprint" not in tomllib.loads(p.read_text())["inference"]


def test_appending_keeps_a_preceding_section_intact(tmp_path):
    p = tmp_path / "config.toml"
    p.write_text('[calendar]\ngoogle_ics_urls = []\n', encoding="utf-8")

    set_section(p, "inference", {"endpoint": "https://h:8443"})

    cfg = tomllib.loads(p.read_text())
    assert "calendar" in cfg and cfg["inference"]["endpoint"] == "https://h:8443"


def test_result_is_always_parseable_toml(tmp_path):
    p = tmp_path / "config.toml"

    set_section(p, "inference", {"endpoint": 'weird "quoted" \\ value', "on": True})

    assert tomllib.loads(p.read_text())["inference"]["endpoint"] == 'weird "quoted" \\ value'
