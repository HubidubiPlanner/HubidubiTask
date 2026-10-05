"""Append-only agent activity log at ~/.local/state/hubidubios/agent.log.

Every skill invocation is logged with its parameters and result, so the
whole loop is auditable after the fact.
"""

import json
import os
from datetime import datetime, timezone
from pathlib import Path


def log_path() -> Path:
    base = os.environ.get("XDG_STATE_HOME")
    root = Path(base) if base else Path.home() / ".local/state"
    return root / "hubidubios" / "agent.log"


def log_event(event: dict, path: Path | None = None) -> None:
    p = path or log_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    record = {"ts": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
              **event}
    with open(p, "a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")
