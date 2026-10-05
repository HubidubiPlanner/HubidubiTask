"""Write one section of the vault's config.toml without disturbing the rest.

The config is hand-edited as often as it is machine-set, so a round-trip through
a TOML serializer is the wrong tool: it would reformat the file and silently drop
every comment the user wrote. Instead we splice a single `[section]` block and
leave every other line byte-identical.

Only the value types the config actually uses are supported (string, bool,
number, list of strings); anything else is a programming error rather than
something to guess at.
"""

from __future__ import annotations

import re
from pathlib import Path

_SECTION_RE = re.compile(r"^\s*\[([^\]]+)\]\s*$")


def _render_value(value) -> str:
    if isinstance(value, bool):          # before int: bool is an int subclass
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return repr(value)
    if isinstance(value, str):
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    if isinstance(value, (list, tuple)):
        return "[" + ", ".join(_render_value(v) for v in value) + "]"
    raise TypeError(f"unsupported TOML value: {value!r}")


def render_section(section: str, values: dict) -> list[str]:
    """The lines for one section. Keys whose value is None are omitted."""
    lines = [f"[{section}]"]
    for key, value in values.items():
        if value is None:
            continue
        lines.append(f"{key} = {_render_value(value)}")
    return lines


def set_section(path: Path, section: str, values: dict) -> None:
    """Replace `[section]` in the TOML at `path`, creating the file if needed.

    Keys absent from `values` are dropped from the section — the caller passes
    the section's complete desired state, so a setting cleared in the UI is
    actually cleared on disk rather than lingering.
    """
    path = Path(path)
    block = render_section(section, values)

    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(block) + "\n", encoding="utf-8")
        return

    lines = path.read_text(encoding="utf-8").split("\n")
    start = end = None
    for i, line in enumerate(lines):
        m = _SECTION_RE.match(line)
        if not m:
            continue
        if m.group(1).strip() == section:
            start = i
        elif start is not None and end is None:
            end = i            # the next section header ends ours
    if start is None:
        # Append, keeping exactly one blank line before the new block.
        while lines and not lines[-1].strip():
            lines.pop()
        lines += ["", *block, ""]
    else:
        if end is None:
            end = len(lines)
        lines[start:end] = block + [""]
    text = "\n".join(lines)
    path.write_text(text if text.endswith("\n") else text + "\n", encoding="utf-8")
