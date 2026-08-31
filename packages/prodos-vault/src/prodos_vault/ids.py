"""Entity id generation.

Ids are `<prefix>_<8 lowercase base32 chars>` from os.urandom. Random, never
sequential, never reused — stable identity is load-bearing for sync (Phase 2)
and the plugin entity registry (Phase 3).
"""

import os
import re

# RFC 4648 base32 alphabet, lowercased. 8 chars = 40 bits of entropy.
_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
_SUFFIX_LEN = 8

PREFIXES = {
    "task": "t",
    "event": "e",
    "note": "n",
    "inbox_item": "i",
}

_ID_RE = re.compile(r"^[teni]_[a-z2-7]+$")


def new_id(entity_type: str) -> str:
    prefix = PREFIXES[entity_type]
    raw = os.urandom(_SUFFIX_LEN)
    suffix = "".join(_ALPHABET[b & 31] for b in raw)
    return f"{prefix}_{suffix}"


def is_valid(entity_id: str) -> bool:
    return bool(_ID_RE.match(entity_id))


def type_of(entity_id: str) -> str | None:
    """Entity type implied by an id's prefix, or None if unrecognized."""
    for etype, prefix in PREFIXES.items():
        if entity_id.startswith(prefix + "_"):
            return etype
    return None
