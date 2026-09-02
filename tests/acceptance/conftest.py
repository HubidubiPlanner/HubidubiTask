"""Fixtures for the Phase 1 acceptance suite (PLAN.md § Phase 1 Acceptance)."""

from datetime import datetime, timedelta, timezone

import pytest
from prodos_vault.formats import ics
from prodos_vault.vault import Vault


@pytest.fixture
def vault(tmp_path):
    v = Vault(tmp_path / "vault")
    v.ensure_layout()
    return v


@pytest.fixture
def big_vault(vault):
    """A synthetic vault with 5,000 entities:
    2,000 tasks, 2,000 notes, 500 inbox items, 500 events."""
    base = datetime(2026, 9, 1, 9, 0, tzinfo=timezone.utc)

    for p in range(40):  # 40 projects x 50 tasks
        lines = [f"---\nproject: project-{p:02d}\n---\n"]
        for t in range(50):
            n = p * 50 + t
            due = f" due:2026-{9 + (n % 3):02d}-{(n % 28) + 1:02d}" if n % 4 == 0 else ""
            lines.append(
                f"- [ ] task number {n} about topic-{n % 97}"
                f"  <!-- id:t_gen{n:05d}{due} ctx:@computer -->")
        (vault.tasks_dir / f"project-{p:02d}.md").write_text("\n".join(lines) + "\n")

    for n in range(2000):
        d = vault.notes_dir / "2026" / f"{(n % 12) + 1:02d}"
        d.mkdir(parents=True, exist_ok=True)
        (d / f"note-{n:04d}.md").write_text(
            f"---\nid: n_gen{n:05d}\ntitle: Note {n} on subject-{n % 89}\n"
            f"updated: 2026-08-31T10:00:00Z\n---\n\n"
            f"Body text mentioning keyword-{n % 71} and [[note-{(n + 1) % 2000:04d}]].\n")

    for day in range(10):  # 10 days x 50 inbox items
        date = f"2026-08-{day + 1:02d}"
        lines = [
            f"- {8 + i // 10:02d}:{(i * 7) % 60:02d} captured thought {day * 50 + i}"
            f"  <!-- id:i_gen{day * 50 + i:05d} -->"
            for i in range(50)
        ]
        (vault.inbox_dir / f"{date}.md").write_text("\n".join(lines) + "\n")

    cal = vault.calendars_dir / "work"
    cal.mkdir(parents=True)
    for n in range(500):
        start = base + timedelta(days=n % 60, hours=n % 8)
        (cal / f"event-{n:04d}.ics").write_text(ics.render_event(
            f"Meeting {n} re agenda-{n % 53}", start,
            start + timedelta(hours=1), f"gen-{n}@prodos-accept"))

    return vault
