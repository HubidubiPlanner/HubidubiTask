"""`prodos watch`: keep index.db current while files change.

watchdog observer (FSEvents on macOS, inotify on Linux) with a 500ms
per-file debounce — a burst of events on one file coalesces into a single
reindex once the file has been quiet for the debounce window.
"""

import threading
import time
from pathlib import Path

from .index import indexer
from .vault import Vault

DEBOUNCE_S = 0.5


class _Handler:
    def __init__(self):
        self.lock = threading.Lock()
        self.pending: dict[str, float] = {}  # path -> last event time

    def dispatch(self, event):
        if getattr(event, "is_directory", False):
            return
        paths = [event.src_path]
        dest = getattr(event, "dest_path", None)
        if dest:
            paths.append(dest)
        now = time.monotonic()
        with self.lock:
            for p in paths:
                self.pending[p] = now

    def due(self) -> list[str]:
        cutoff = time.monotonic() - DEBOUNCE_S
        with self.lock:
            ready = [p for p, t in self.pending.items() if t <= cutoff]
            for p in ready:
                del self.pending[p]
        return ready


def run(vault: Vault, quiet: bool = False) -> None:
    from watchdog.observers import Observer

    conn = indexer.open_index(vault)  # initial sweep catches offline edits
    handler = _Handler()
    observer = Observer()
    observer.schedule(handler, str(vault.root), recursive=True)
    observer.start()
    if not quiet:
        print(f"watching {vault.root} (debounce {int(DEBOUNCE_S * 1000)}ms)")
    try:
        while True:
            time.sleep(0.1)
            for p in handler.due():
                path = Path(p)
                if vault.classify(path) is None:
                    continue
                n = indexer.index_file(conn, vault, path)
                if not quiet:
                    rel = path.name
                    print(f"indexed {rel}: {n} entities" if path.exists()
                          else f"removed {rel}")
    except KeyboardInterrupt:
        pass
    finally:
        observer.stop()
        observer.join()
        conn.close()
