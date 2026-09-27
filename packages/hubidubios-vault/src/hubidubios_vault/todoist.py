"""One-time, one-way import of active Todoist projects and tasks into the vault.

A move away from Todoist, not a sync: Todoist is read once and never written
to. Each Todoist project becomes a project, each open task a next action filed
under it, the Inbox's tasks loose next actions. Re-running is safe — what was
imported before is remembered in .hubidubios/todoist-import.json and skipped,
so nothing is duplicated and nothing you changed since is overwritten. Tasks
completed in Todoist are not fetched at all.

What does not carry over, because the vault has no place for it: sections,
labels, priorities, descriptions, recurrence (only the next date is kept), and
nesting — sub-projects become projects of their own, sub-tasks sit beside
their parent in the same project. The summary counts what was dropped so it is
never silent.

The API token is used for the one run and never stored.
"""

from __future__ import annotations

import json
import os
import re
import urllib.parse
import urllib.request

from . import apply
from .entities import now_rfc3339

API = "https://api.todoist.com/api/v1"
STATE_FILE = "todoist-import.json"

_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"


def _new_id(prefix: str) -> str:
    # Same shape as the GTD skills' ids: <prefix>_<8 base32 chars>, random.
    return prefix + "_" + "".join(_ALPHABET[b & 31] for b in os.urandom(8))


def _slug(title: str) -> str:
    # Must match upsert_project's file naming and the phone's
    # ProjectTree.slugOf, which files legacy tasks under a project by slug.
    return re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-") or "project"


def _default_fetch(path: str, token: str, params: dict) -> dict | list:
    url = f"{API}{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}", "User-Agent": "hubidubios/1"})
    with urllib.request.urlopen(req, timeout=30) as r:  # noqa: S310 (fixed host)
        return json.loads(r.read())


def _all(fetch, path: str, token: str) -> list[dict]:
    """Every page of a list endpoint. v1 answers {results, next_cursor};
    a bare list (older shape) is taken as the whole answer."""
    out: list[dict] = []
    cursor = None
    while True:
        params = {"limit": 200}
        if cursor:
            params["cursor"] = cursor
        page = fetch(path, token, params)
        if isinstance(page, list):
            return out + page
        out += page.get("results") or []
        cursor = page.get("next_cursor")
        if not cursor:
            return out


def _is_inbox(p: dict) -> bool:
    return bool(p.get("inbox_project") or p.get("is_inbox_project") or p.get("is_inbox"))


def _due(t: dict) -> str | None:
    due = t.get("due") or {}
    date = (due.get("date") or "")[:10]
    return date if re.fullmatch(r"\d{4}-\d{2}-\d{2}", date) else None


def _existing_projects(vault) -> dict[str, str]:
    """slug -> id of the projects already in the vault. A Todoist project of
    the same name joins the existing one: writing it anew would overwrite that
    file, since a project's file is named by its slug."""
    out: dict[str, str] = {}
    if not vault.projects_dir.exists():
        return out
    for f in vault.projects_dir.glob("*.md"):
        m = re.search(r"^id:\s*(\S+)", f.read_text(encoding="utf-8"), re.M)
        if m:
            out[f.stem] = m.group(1)
    return out


def _load_state(vault) -> dict:
    path = vault.hubidubios_dir / STATE_FILE
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"projects": {}, "tasks": {}}


def _save_state(vault, state: dict) -> None:
    vault.hubidubios_dir.mkdir(parents=True, exist_ok=True)
    (vault.hubidubios_dir / STATE_FILE).write_text(
        json.dumps(state, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def import_all(vault, token: str, fetch=_default_fetch) -> dict:
    """Import every active project and open task. Returns counts for the summary."""
    projects = _all(fetch, "/projects", token)
    tasks = _all(fetch, "/tasks", token)
    state = _load_state(vault)
    stamp = now_rfc3339()
    res = {"projects": 0, "merged": 0, "tasks": 0, "skipped": 0, "dated": 0,
           "recurring": 0, "with_description": 0, "with_labels": 0,
           "subtasks": 0, "in_sections": 0}

    # Projects first, so every task has somewhere to land.
    by_todoist: dict[str, tuple[str, str] | None] = {}   # todoist id -> (our id, slug) | None=inbox
    existing = _existing_projects(vault)
    for p in projects:
        if p.get("is_archived") or p.get("is_deleted"):
            continue
        tid = str(p["id"])
        if _is_inbox(p):
            by_todoist[tid] = None
            continue
        title = (p.get("name") or "Untitled").strip()
        if tid in state["projects"]:
            by_todoist[tid] = tuple(state["projects"][tid])
            continue
        slug = _slug(title)
        if slug in existing:
            pid = existing[slug]
            res["merged"] += 1
        else:
            pid = _new_id("p")
            apply.upsert_project(vault, {"id": pid, "title": title, "status": "active",
                                         "created": stamp, "updated": stamp})
            existing[slug] = pid
            res["projects"] += 1
        state["projects"][tid] = [pid, slug]
        by_todoist[tid] = (pid, slug)

    for t in tasks:
        if t.get("checked") or t.get("is_completed") or t.get("is_deleted"):
            continue
        tid = str(t["id"])
        if tid in state["tasks"]:
            res["skipped"] += 1
            continue
        home = by_todoist.get(str(t.get("project_id") or ""))
        # One task is one checkbox line: a newline in the title would split it.
        text = " ".join((t.get("content") or "").split()) or "(untitled)"
        d = {"id": _new_id("t"), "text": text,
             "updated": stamp}
        if home:
            d["parent"], d["project"] = home
        if due := _due(t):
            d["due"] = due
            res["dated"] += 1
        apply.upsert_next_action(vault, d)
        state["tasks"][tid] = d["id"]
        res["tasks"] += 1
        res["recurring"] += bool((t.get("due") or {}).get("is_recurring"))
        res["with_description"] += bool((t.get("description") or "").strip())
        res["with_labels"] += bool(t.get("labels"))
        res["subtasks"] += bool(t.get("parent_id"))
        res["in_sections"] += bool(t.get("section_id"))

    _save_state(vault, state)
    return res


def summary(res: dict) -> list[str]:
    """The import's result in words, including what did not carry over."""
    lines = [f"todoist import: {res['projects']} new projects"
             f" ({res['merged']} joined existing ones of the same name),"
             f" {res['tasks']} tasks ({res['dated']} with a date),"
             f" {res['skipped']} already imported before"]
    for n, what in [(res["with_description"], "had a description"),
                    (res["with_labels"], "had labels"),
                    (res["recurring"], "were recurring (only the next date kept)"),
                    (res["subtasks"], "were sub-tasks (now beside their parent)"),
                    (res["in_sections"], "were in a section")]:
        if n:
            lines.append(f"  not carried over: {n} {what}")
    return lines
