"""Phase 1 acceptance, mirroring PLAN.md:

- 5,000 entities index in under 10 seconds.
- Full-text search returns in under 50ms.
- Deleting index.db and rebuilding produces identical query results.
- Editing a task in vim appears in `prodos task list` within one second.
- `prodos capture` completes in under 100ms with no index running.
"""

import os
import subprocess
import sys
import time
from pathlib import Path

from prodos_vault.index import indexer, queries

PRODOS_BIN = Path(sys.executable).parent / "prodos"


def _env(vault):
    return {**os.environ, "PRODOS_VAULT": str(vault.root)}


def test_5000_entities_index_under_10s(big_vault):
    t0 = time.perf_counter()
    n = indexer.rebuild(big_vault)
    elapsed = time.perf_counter() - t0
    assert n == 5000
    assert elapsed < 10.0, f"rebuild took {elapsed:.2f}s"


def test_fts_search_under_50ms(big_vault):
    indexer.rebuild(big_vault)
    conn = indexer.open_index(big_vault, do_sweep=False)
    queries.search(conn, "keyword-13")  # warm the page cache
    t0 = time.perf_counter()
    hits = queries.search(conn, "topic-42")
    elapsed = time.perf_counter() - t0
    conn.close()
    assert hits
    assert elapsed < 0.050, f"search took {elapsed * 1000:.1f}ms"


def test_rebuild_produces_identical_results(big_vault):
    indexer.rebuild(big_vault)

    def snapshot():
        conn = indexer.open_index(big_vault, do_sweep=False)
        snap = (
            queries.counts(conn),
            queries.task_list(conn, include_done=True),
            queries.inbox_items(conn),
            queries.agenda(conn, "2026-01-01", "2026-12-31"),
            queries.search(conn, "subject-42", limit=50),
            [tuple(r) for r in conn.execute(
                "SELECT id, type, updated, file FROM entities ORDER BY id")],
        )
        conn.close()
        return snap

    before = snapshot()
    big_vault.index_path.unlink()
    indexer.rebuild(big_vault)
    assert snapshot() == before


def test_vim_edit_visible_within_one_second(vault):
    subprocess.run([PRODOS_BIN, "index", "rebuild"], env=_env(vault), check=True)
    path = vault.tasks_dir / "home.md"
    path.write_text("---\nproject: home\n---\n\n"
                    "- [ ] existing  <!-- id:t_exist001 -->\n")
    # the vim edit
    with open(path, "a") as f:
        f.write("- [ ] added in vim  <!-- id:t_vimedit1 -->\n")
    t0 = time.perf_counter()
    out = subprocess.run([PRODOS_BIN, "task", "list"], env=_env(vault),
                         capture_output=True, text=True, check=True).stdout
    elapsed = time.perf_counter() - t0
    assert "added in vim" in out
    assert elapsed < 1.0, f"task list took {elapsed:.2f}s after external edit"


def test_watcher_indexes_within_debounce_window(vault):
    indexer.rebuild(vault)
    proc = subprocess.Popen([PRODOS_BIN, "watch"], env=_env(vault),
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1.5)  # let the observer start
        (vault.tasks_dir / "w.md").write_text(
            "---\nproject: w\n---\n\n- [ ] watched  <!-- id:t_watched1 -->\n")
        t0 = time.perf_counter()
        deadline = t0 + 5.0
        found = False
        while time.perf_counter() < deadline:
            conn = indexer.open_index(vault, do_sweep=False)
            found = queries.find_task(conn, "t_watched1") is not None
            conn.close()
            if found:
                break
            time.sleep(0.05)
        elapsed = time.perf_counter() - t0
        assert found, "watcher never indexed the new file"
        # 500ms debounce + fs event latency; anything past 2s means the
        # watcher path is broken and only a sweep would have caught it.
        assert elapsed < 2.0, f"watcher took {elapsed:.2f}s"
    finally:
        proc.terminate()
        proc.wait(timeout=5)


def test_capture_under_100ms_no_index_running(vault):
    env = _env(vault)
    subprocess.run([PRODOS_BIN, "capture", "warmup"], env=env, check=True)
    timings = []
    for i in range(3):
        t0 = time.perf_counter()
        subprocess.run([PRODOS_BIN, "capture", f"quick thought {i}"],
                       env=env, check=True)
        timings.append(time.perf_counter() - t0)
    best = min(timings)
    assert best < 0.100, f"capture took {best * 1000:.1f}ms"
    assert not vault.index_path.exists(), "capture must not touch the index"
    inbox_files = list(vault.inbox_dir.glob("*.md"))
    assert len(inbox_files) == 1
    assert len(inbox_files[0].read_text().splitlines()) == 4
