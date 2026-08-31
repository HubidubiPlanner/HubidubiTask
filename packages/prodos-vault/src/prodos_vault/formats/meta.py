"""Inline HTML-comment metadata: `<!-- id:t_a1b2 due:2026-09-05 ctx:@computer -->`.

Invisible in any markdown renderer, survives round-tripping through editors,
parses trivially. Values must not contain whitespace; keys are ordered so
serialization is deterministic.
"""

import re

_COMMENT_RE = re.compile(r"<!--\s*(.*?)\s*-->")
_PAIR_RE = re.compile(r"([a-z]+):(\S+)")

# Canonical key order for serialization.
_KEY_ORDER = ["id", "due", "done", "ctx", "updated"]


def extract(line: str) -> tuple[str, dict[str, str]]:
    """Split a line into (text without the metadata comment, metadata dict)."""
    meta: dict[str, str] = {}
    m = _COMMENT_RE.search(line)
    if not m:
        return line.rstrip(), meta
    for k, v in _PAIR_RE.findall(m.group(1)):
        meta[k] = v
    text = (line[: m.start()] + line[m.end():]).rstrip()
    return text, meta


def render(meta: dict[str, str]) -> str:
    keys = [k for k in _KEY_ORDER if k in meta]
    keys += [k for k in meta if k not in _KEY_ORDER]
    body = " ".join(f"{k}:{meta[k]}" for k in keys)
    return f"<!-- {body} -->"
