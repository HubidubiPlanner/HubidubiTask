"""Content-addressed media blob store.

Image/audio note bytes never enter the entity JSON. They live as a plain,
browsable file in notes/ (the materialized copy) and move between devices over
the sync blob channel, addressed by SHA-256. `.hubidubios/blobs/<sha256>` is a
staging/cache area used while a blob and its note change are in flight; once the
note change is applied the bytes are materialized into notes/, and reads resolve
to whichever copy exists.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

MAX_BLOB_BYTES = 25 * 1024 * 1024  # protects the stdlib server (whole body in RAM)


class BlobError(ValueError):
    pass


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _valid(sha: str) -> bool:
    return len(sha) == 64 and all(c in "0123456789abcdef" for c in sha)


def staged_path(vault, sha: str) -> Path:
    if not _valid(sha):
        raise BlobError(f"bad sha256: {sha!r}")
    return vault.blobs_dir / sha


def store(vault, data: bytes, expected_sha: str | None = None) -> str:
    """Verify (optionally against expected_sha) and stage bytes; return the sha."""
    if len(data) > MAX_BLOB_BYTES:
        raise BlobError(f"blob too large: {len(data)} > {MAX_BLOB_BYTES}")
    sha = sha256_of(data)
    if expected_sha is not None and sha != expected_sha.lower():
        raise BlobError("sha256 mismatch")
    p = staged_path(vault, sha)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
    return sha


def _materialized_path(vault, sha: str) -> Path | None:
    """The notes/ file whose media_sha256 == sha, via the index (if any)."""
    try:
        from .index import indexer
    except Exception:
        return None
    if not vault.index_path.exists():
        return None
    conn = indexer.open_index(vault, do_sweep=False)
    try:
        row = conn.execute(
            "SELECT file FROM entities WHERE type='note' "
            "AND json_extract(data, '$.media_sha256') = ?", (sha,)).fetchone()
    except Exception:
        return None
    finally:
        conn.close()
    if not row:
        return None
    p = vault.root / row["file"]
    return p if p.exists() else None


def resolve(vault, sha: str) -> Path | None:
    """Where the bytes for sha can be read: the materialized note file, else the
    staged copy, else None."""
    if not _valid(sha):
        return None
    mat = _materialized_path(vault, sha)
    if mat:
        return mat
    p = staged_path(vault, sha)
    return p if p.exists() else None


def get(vault, sha: str) -> bytes | None:
    p = resolve(vault, sha)
    return p.read_bytes() if p else None
