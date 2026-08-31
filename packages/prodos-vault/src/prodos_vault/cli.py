"""prodos CLI.

Heavy imports (sqlite3, yaml, icalendar, watchdog) are deferred into the
subcommand handlers: `prodos capture` must complete in under 100ms with no
index running, so its path is interpreter start + one file append.
"""

import argparse
import sys
from datetime import date, datetime, timedelta

from .vault import Vault


def _open(vault: Vault):
    from .index import indexer

    return indexer.open_index(vault)


# ---------------------------------------------------------------- capture

def cmd_capture(vault: Vault, args) -> int:
    from .formats.inbox_md import format_item_line
    from .ids import new_id

    text = " ".join(args.text).strip()
    if not text:
        print("nothing to capture", file=sys.stderr)
        return 1
    now = datetime.now()
    path = vault.inbox_dir / f"{now:%Y-%m-%d}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    line = format_item_line(text, f"{now:%H:%M}", new_id("inbox_item"))
    with open(path, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    return 0


# ---------------------------------------------------------------- tasks

def cmd_task_add(vault: Vault, args) -> int:
    from .formats import tasks_md
    from .index import indexer

    path = vault.tasks_dir / f"{args.project}.md"
    task_id = tasks_md.append_task(
        path, " ".join(args.text).strip(), due=args.due, ctx=args.ctx)
    conn = indexer.open_index(vault, do_sweep=False)  # write-through
    indexer.index_file(conn, vault, path)
    conn.close()
    print(task_id)
    return 0


def cmd_task_list(vault: Vault, args) -> int:
    from .index import queries

    conn = _open(vault)
    tasks = queries.task_list(
        conn, project=args.project, context=args.ctx, include_done=args.all)
    conn.close()
    for t in tasks:
        box = "[x]" if t["done"] else "[ ]"
        extra = " ".join(
            filter(None, [f"due:{t['due']}" if t.get("due") else "",
                          t.get("context") or ""]))
        extra = f"  {extra}" if extra else ""
        print(f"{t['id']}  {box} {t['text']}  ({t['project']}){extra}")
    return 0


def cmd_task_done(vault: Vault, args) -> int:
    from .formats import tasks_md
    from .index import indexer, queries

    conn = _open(vault)
    found = queries.find_task(conn, args.id)
    if not found:
        print(f"no task with id {args.id}", file=sys.stderr)
        conn.close()
        return 1
    _task, rel = found
    path = vault.root / rel
    if not tasks_md.mark_done(path, args.id, date.today().isoformat()):
        print(f"task {args.id} not found in {rel}", file=sys.stderr)
        conn.close()
        return 1
    indexer.index_file(conn, vault, path)
    conn.close()
    print(f"done: {args.id}")
    return 0


# ---------------------------------------------------------------- notes

def cmd_note_new(vault: Vault, args) -> int:
    from .entities import Note, now_rfc3339
    from .formats import notes_md
    from .ids import new_id

    title = " ".join(args.title).strip()
    now = datetime.now()
    slug = notes_md.slugify(title)
    path = vault.notes_dir / f"{now:%Y}" / f"{now:%m}" / f"{slug}.md"
    if path.exists():
        print(f"note exists: {path}", file=sys.stderr)
        return 1
    path.parent.mkdir(parents=True, exist_ok=True)
    ts = now_rfc3339()
    note = Note(id=new_id("note"), updated=ts, title=title,
                tags=args.tags or [], created=ts)
    path.write_text(notes_md.render(note), encoding="utf-8")
    print(path)
    return 0


# ---------------------------------------------------------------- agenda

def cmd_agenda(vault: Vault, args) -> int:
    from .index import queries

    conn = _open(vault)
    start = date.today()
    end = start + timedelta(days=max(args.days - 1, 0))
    result = queries.agenda(conn, start.isoformat(), end.isoformat())
    conn.close()

    by_day: dict[str, list[str]] = {}
    for ev in result["events"]:
        day = ev["dtstart"][:10]
        when = "all day" if ev["all_day"] else ev["dtstart"][11:16]
        by_day.setdefault(day, []).append(f"  {when}  {ev['summary']}  [{ev['calendar']}]")
    for t in result["tasks"]:
        by_day.setdefault(t["due"], []).append(
            f"  due    {t['text']}  ({t['project']}) {t['id']}")
    if not by_day:
        print(f"nothing scheduled through {end.isoformat()}")
        return 0
    for day in sorted(by_day):
        print(day)
        for line in sorted(by_day[day]):
            print(line)
    return 0


# ---------------------------------------------------------------- search / inbox

def cmd_search(vault: Vault, args) -> int:
    from .index import queries

    conn = _open(vault)
    try:
        hits = queries.search(conn, " ".join(args.query))
    except Exception as e:  # fts5 syntax errors from raw user input
        print(f"search error: {e}", file=sys.stderr)
        return 1
    finally:
        conn.close()
    for h in hits:
        label = h.get("text") or h.get("title") or h.get("summary") or ""
        print(f"{h['id']}  [{h['type']}]  {label}  ({h['_file']})")
    return 0


def cmd_inbox(vault: Vault, args) -> int:
    from .index import queries

    conn = _open(vault)
    items = queries.inbox_items(conn)
    conn.close()
    for i in items:
        print(f"{i['id']}  {i['date']} {i['time']}  {i['text']}")
    if not items:
        print("inbox empty")
    return 0


# ---------------------------------------------------------------- index / watch

def cmd_index_rebuild(vault: Vault, args) -> int:
    from .index import indexer

    n = indexer.rebuild(vault)
    print(f"indexed {n} entities from {vault.root}")
    return 0


def cmd_watch(vault: Vault, args) -> int:
    from . import watch

    watch.run(vault)
    return 0


# ---------------------------------------------------------------- parser

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="prodos", description="Local-first vault CLI")
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("capture", help="append to today's GTD inbox")
    sp.add_argument("text", nargs="+")
    sp.set_defaults(func=cmd_capture)

    tp = sub.add_parser("task", help="manage tasks")
    tsub = tp.add_subparsers(dest="task_command", required=True)
    sp = tsub.add_parser("add")
    sp.add_argument("text", nargs="+")
    sp.add_argument("--project", default="inbox")
    sp.add_argument("--due", help="YYYY-MM-DD")
    sp.add_argument("--ctx", help="@computer, @errands, ...")
    sp.set_defaults(func=cmd_task_add)
    sp = tsub.add_parser("list")
    sp.add_argument("--project")
    sp.add_argument("--ctx")
    sp.add_argument("--all", action="store_true", help="include done tasks")
    sp.set_defaults(func=cmd_task_list)
    sp = tsub.add_parser("done")
    sp.add_argument("id")
    sp.set_defaults(func=cmd_task_done)

    np = sub.add_parser("note", help="manage notes")
    nsub = np.add_subparsers(dest="note_command", required=True)
    sp = nsub.add_parser("new")
    sp.add_argument("title", nargs="+")
    sp.add_argument("--tags", nargs="*")
    sp.set_defaults(func=cmd_note_new)

    sp = sub.add_parser("agenda", help="events and due tasks")
    sp.add_argument("--days", type=int, default=7)
    sp.set_defaults(func=cmd_agenda)

    sp = sub.add_parser("search", help="full-text search")
    sp.add_argument("query", nargs="+")
    sp.set_defaults(func=cmd_search)

    sp = sub.add_parser("inbox", help="list unprocessed inbox items")
    sp.set_defaults(func=cmd_inbox)

    ip = sub.add_parser("index", help="index maintenance")
    isub = ip.add_subparsers(dest="index_command", required=True)
    sp = isub.add_parser("rebuild")
    sp.set_defaults(func=cmd_index_rebuild)

    sp = sub.add_parser("watch", help="watch the vault and keep the index fresh")
    sp.set_defaults(func=cmd_watch)

    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    vault = Vault.locate()
    return args.func(vault, args)


if __name__ == "__main__":
    sys.exit(main())
