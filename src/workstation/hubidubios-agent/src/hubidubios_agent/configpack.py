"""Push HubidubiOS's selfhostedai configuration to the inference server.

HubidubiOS owns the whole selfhostedai config: the personas and chain-of-thought that
give the assistant its behaviour, and the vision worker that lets it read a
photo. Rather than have the user hand-edit JSON on the server, we ship the
canonical config with the package and upload it over the same encrypted session
the agent already uses for inference.

Wire format (POST /api/admin/config/upload, AES-GCM inside the session):

    {"sessionId": ..., "pack": "<base64 zip>", "validate_only": true}
 -> {"success", "validated", "applied", "restartRequired", "errors": [...]}

The server stages the zip in a throwaway directory, validates it (every
persona_chain id must resolve, every model id a worker references must exist,
server.port must be present), and only then copies the pack's files over the
deploy root and restarts. `validate_only` performs no file writes at all, so it
is always safe to run first — and this module does exactly that before applying.

Apply is an *overlay*: only files carried in the pack are overwritten. The
server's other presets under config/configs/ survive, so a bad config can be
rolled back with POST /api/admin/reconfigure.
"""

from __future__ import annotations

import base64
import io
import json
import zipfile
from pathlib import Path

CONFIG_NAME = "config.json"
ENDPOINT = "/api/admin/config/upload"


def canonical_config_path() -> Path:
    """The config HubidubiOS ships.

    It lives inside the package (not in a repo-relative directory) so it travels
    with the installed wheel — `config push` has to work on a machine that has
    only the package, never a source checkout.
    """
    path = Path(__file__).resolve().parent / "data" / "selfhostedai-config.json"
    if not path.is_file():
        raise FileNotFoundError(f"packaged selfhostedai config missing: {path}")
    return path


def build_pack(config_path: Path | None = None) -> bytes:
    """Zip the config pack. The config is self-contained (no @@include@@), so
    the pack is a single file — nothing can fail the server's include check."""
    path = Path(config_path) if config_path else canonical_config_path()
    text = path.read_text(encoding="utf-8")
    if "@@include" in text:
        # Includes resolve against the *deploy root*, so an include here would
        # require shipping its target too; keeping the config flat avoids a
        # class of fatal boot errors.
        raise ValueError(f"{path} contains @@include@@; the pack must be self-contained")
    json.loads(text)  # fail here rather than on the server

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(CONFIG_NAME, text)
    return buf.getvalue()


def upload(client, pack: bytes, validate_only: bool = True,
           language: str | None = None) -> dict:
    """Send the pack over `client`'s encrypted session and return the verdict."""
    body: dict = {"pack": base64.b64encode(pack).decode(), "validate_only": validate_only}
    if language:
        body["language"] = language
    return client.admin_request(ENDPOINT, body)


def push(client, config_path: Path | None = None, apply: bool = False,
         language: str | None = None) -> dict:
    """Validate, then optionally apply.

    Applying without a passing validation is never worth the risk: the server
    restarts into whatever it was given, so a config it cannot load takes the
    inference server down.
    """
    pack = build_pack(config_path)
    verdict = upload(client, pack, validate_only=True, language=language)
    if not verdict.get("success"):
        return verdict
    if not apply:
        return verdict
    return upload(client, pack, validate_only=False, language=language)
