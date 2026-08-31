"""Task files: one markdown file per project, checkbox lines with inline metadata.

    ---
    project: prodos
    tags: [dev]
    ---

    - [ ] Design plugin manifest schema  <!-- id:t_a1b2 due:2026-09-05 ctx:@computer -->
    - [x] Pick vault layout  <!-- id:t_c3d4 done:2026-08-30 -->

Mutations are line-level edits on the original text, never a full re-serialize,
so hand-written surroundings (prose, blank lines, comments) are preserved
byte-for-byte.
"""

import re
from pathlib import Path

from ..entities import Task, now_rfc3339
from ..ids import new_id
from . import meta as _meta

_CHECKBOX_RE = re.compile(r"^\s*- \[( |x|X)\] (.*)$")
_FM_DELIM = "---"


def _parse_frontmatter(lines: list[str]) -> tuple[dict, int]:
    """Return (frontmatter dict, index of first body line)."""
    if not lines or lines[0].strip() != _FM_DELIM:
        return {}, 0
    for i in range(1, len(lines)):
        if lines[i].strip() == _FM_DELIM:
            import yaml

            raw = "\n".join(lines[1:i])
            try:
                fm = yaml.safe_load(raw) or {}
            except yaml.YAMLError:
                fm = {}
            return (fm if isinstance(fm, dict) else {}), i + 1
    return {}, 0


def parse_file(text: str, path: Path, file_mtime_rfc3339: str) -> list[tuple[Task, int]]:
    """Return (task, 1-based line number) pairs."""
    lines = text.split("\n")
    fm, body_start = _parse_frontmatter(lines)
    project = str(fm.get("project") or path.stem)
    file_tags = fm.get("tags") or []
    if not isinstance(file_tags, list):
        file_tags = [str(file_tags)]

    tasks: list[tuple[Task, int]] = []
    for lineno, line in enumerate(lines[body_start:], start=body_start + 1):
        m = _CHECKBOX_RE.match(line)
        if not m:
            continue
        done = m.group(1) in ("x", "X")
        task_text, md = _meta.extract(m.group(2))
        if "id" not in md:
            # Hand-written line without an id: still index it, keyed to a
            # deterministic placeholder is worse than skipping — an entity
            # without stable identity breaks sync. Skip; `prodos index` logs it.
            continue
        task = Task(
            id=md["id"],
            updated=md.get("updated", file_mtime_rfc3339),
            text=task_text.strip(),
            done=done,
            project=project,
            due=md.get("due"),
            done_date=md.get("done"),
            context=md.get("ctx"),
            tags=[str(t) for t in file_tags],
        )
        tasks.append((task, lineno))
    return tasks


def format_task_line(text: str, *, task_id: str | None = None,
                     due: str | None = None, ctx: str | None = None) -> str:
    md: dict[str, str] = {"id": task_id or new_id("task")}
    if due:
        md["due"] = due
    if ctx:
        md["ctx"] = ctx
    md["updated"] = now_rfc3339()
    return f"- [ ] {text}  {_meta.render(md)}"


def new_project_file(project: str) -> str:
    return f"---\nproject: {project}\n---\n\n"


def append_task(path: Path, text: str, *, due: str | None = None,
                ctx: str | None = None) -> str:
    """Append a task line to a project file, creating it if needed.

    Returns the new task id.
    """
    line = format_task_line(text, due=due, ctx=ctx)
    task_id = _meta.extract(line)[1]["id"]
    if path.exists():
        content = path.read_text(encoding="utf-8")
        if content and not content.endswith("\n"):
            content += "\n"
        content += line + "\n"
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        content = new_project_file(path.stem) + line + "\n"
    path.write_text(content, encoding="utf-8")
    return task_id


def mark_done(path: Path, task_id: str, done_date: str) -> bool:
    """Flip a task's checkbox to [x] and stamp done/updated. Line-level edit."""
    lines = path.read_text(encoding="utf-8").split("\n")
    for i, line in enumerate(lines):
        m = _CHECKBOX_RE.match(line)
        if not m:
            continue
        text, md = _meta.extract(m.group(2))
        if md.get("id") != task_id:
            continue
        md["done"] = done_date
        md["updated"] = now_rfc3339()
        indent = line[: len(line) - len(line.lstrip())]
        lines[i] = f"{indent}- [x] {text.strip()}  {_meta.render(md)}"
        path.write_text("\n".join(lines), encoding="utf-8")
        return True
    return False
