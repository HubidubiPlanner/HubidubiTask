import time

from prodos_vault.formats import tasks_md
from prodos_vault.index import indexer

from prodos_sync import changelog, db


def _conns(vault):
    index_conn = indexer.open_index(vault)
    sync_conn = db.connect(db.sync_db_path(vault))
    return index_conn, sync_conn


def test_capture_emits_upserts_then_nothing(vault):
    tasks_md.append_task(vault.tasks_dir / "p.md", "one")
    tasks_md.append_task(vault.tasks_dir / "p.md", "two")
    index_conn, sync_conn = _conns(vault)
    indexer.sweep(index_conn, vault)

    assert changelog.capture(index_conn, sync_conn) == 2
    assert changelog.capture(index_conn, sync_conn) == 0  # idempotent

    feed = changelog.feed(sync_conn, since=0)
    assert len(feed["changes"]) == 2
    assert all(c["op"] == "upsert" and c["device_id"] == "_local"
               for c in feed["changes"])
    assert feed["cursor"] == 2
    assert not feed["has_more"]


def test_cursor_is_monotonic_and_resumable(vault):
    index_conn, sync_conn = _conns(vault)
    tasks_md.append_task(vault.tasks_dir / "p.md", "first")
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)
    cursor = changelog.feed(sync_conn, since=0)["cursor"]

    tasks_md.append_task(vault.tasks_dir / "p.md", "second")
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)

    feed = changelog.feed(sync_conn, since=cursor)
    assert len(feed["changes"]) == 1
    assert feed["changes"][0]["data"]["text"] == "second"
    assert feed["cursor"] > cursor
    # empty poll: cursor unchanged
    assert changelog.feed(sync_conn, since=feed["cursor"])["cursor"] == feed["cursor"]


def test_edit_emits_single_upsert_with_new_content(vault):
    index_conn, sync_conn = _conns(vault)
    task_id = tasks_md.append_task(vault.tasks_dir / "p.md", "to finish")
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)
    cursor = changelog.feed(sync_conn, 0)["cursor"]

    tasks_md.mark_done(vault.tasks_dir / "p.md", task_id, "2026-08-31")
    indexer.sweep(index_conn, vault)
    assert changelog.capture(index_conn, sync_conn) == 1
    change = changelog.feed(sync_conn, cursor)["changes"][0]
    assert change["id"] == task_id
    assert change["data"]["done"] is True


def test_delete_becomes_tombstone(vault):
    index_conn, sync_conn = _conns(vault)
    tasks_md.append_task(vault.tasks_dir / "p.md", "doomed")
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)
    cursor = changelog.feed(sync_conn, 0)["cursor"]

    (vault.tasks_dir / "p.md").unlink()
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)

    feed = changelog.feed(sync_conn, cursor)
    assert len(feed["changes"]) == 1
    ch = feed["changes"][0]
    assert ch["op"] == "delete" and ch["data"] is None
    assert changelog.is_tombstoned(sync_conn, ch["id"])


def test_rebuild_emits_no_changes(vault):
    index_conn, sync_conn = _conns(vault)
    tasks_md.append_task(vault.tasks_dir / "p.md", "stable")
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)

    index_conn.close()
    vault.index_path.unlink()
    indexer.rebuild(vault)
    index_conn = indexer.open_index(vault)
    assert changelog.capture(index_conn, sync_conn) == 0


def test_feed_collapses_to_latest_per_entity(vault):
    index_conn, sync_conn = _conns(vault)
    path = vault.tasks_dir / "p.md"
    task_id = tasks_md.append_task(path, "v1")
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)
    tasks_md.mark_done(path, task_id, "2026-08-31")
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)

    feed = changelog.feed(sync_conn, since=0)  # both rows in one batch
    assert len(feed["changes"]) == 1
    assert feed["changes"][0]["data"]["done"] is True
    assert feed["cursor"] == 2  # cursor still advances past both rows


def test_tombstone_purge_respects_retention(vault, monkeypatch):
    index_conn, sync_conn = _conns(vault)
    tasks_md.append_task(vault.tasks_dir / "p.md", "old delete")
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)
    (vault.tasks_dir / "p.md").unlink()
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn)

    assert changelog.purge_expired_tombstones(sync_conn) == 0  # fresh: kept

    sync_conn.execute("UPDATE changes SET ts = ts - ?",
                      (changelog.TOMBSTONE_RETENTION_S + 60,))
    assert changelog.purge_expired_tombstones(sync_conn) == 1
