"""Pairing and device tokens.

Pairing: `prodos-sync pair` mints a one-time token with a 5-minute window;
the client POSTs it to /v1/pair and receives a long-lived device token.
Only sha256 hashes of tokens are stored.
"""

import hashlib
import secrets
import time
from datetime import datetime, timezone

PAIR_WINDOW_S = 300


def _h(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def mint_pair_token(sync_conn) -> str:
    token = secrets.token_urlsafe(24)
    with sync_conn:
        sync_conn.execute("DELETE FROM pair_tokens WHERE expires < ?", (time.time(),))
        sync_conn.execute(
            "INSERT INTO pair_tokens (token_hash, expires) VALUES (?, ?)",
            (_h(token), time.time() + PAIR_WINDOW_S))
    return token


def redeem_pair_token(sync_conn, token: str, device_name: str) -> dict | None:
    """One-time token -> device registration, or None if invalid/expired."""
    row = sync_conn.execute(
        "SELECT token_hash FROM pair_tokens WHERE token_hash = ? AND expires >= ?",
        (_h(token), time.time())).fetchone()
    if not row:
        return None
    device_id = "d_" + secrets.token_hex(6)
    device_token = secrets.token_urlsafe(32)
    with sync_conn:
        sync_conn.execute("DELETE FROM pair_tokens WHERE token_hash = ?",
                          (row["token_hash"],))
        sync_conn.execute(
            "INSERT INTO devices (id, name, token_hash, created) VALUES (?, ?, ?, ?)",
            (device_id, device_name, _h(device_token), _now()))
    return {"device_id": device_id, "device_token": device_token}


def authenticate(sync_conn, bearer_token: str) -> str | None:
    """Bearer token -> device id, or None."""
    row = sync_conn.execute(
        "SELECT id FROM devices WHERE token_hash = ?", (_h(bearer_token),)).fetchone()
    if not row:
        return None
    with sync_conn:
        sync_conn.execute("UPDATE devices SET last_seen = ? WHERE id = ?",
                          (_now(), row["id"]))
    return row["id"]
