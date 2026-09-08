"""Apply pushed client changes to vault files.

Server-side rules (field-level merge is the client's job, Phase 5):
- Last-write-wins per entity by `updated` (RFC 3339 compares lexicographically).
- Deletion beats edit: ids are never reused, so an upsert against a
  tombstoned id is by definition a concurrent modification and is rejected.

File surgery lives in hubidubios_vault.apply (shared with the view bridge);
this module owns the policy and the changelog attribution.
"""

from pathlib import Path

from hubidubios_vault import apply as vault_apply
from hubidubios_vault.index import indexer

from . import changelog


def _state_file(sync_conn, entity_id: str) -> str | None:
    row = sync_conn.execute(
        "SELECT file FROM entity_state WHERE entity_id = ?", (entity_id,)).fetchone()
    return row["file"] if row else None


def apply_changes(vault, index_conn, sync_conn, device_id: str,
                  incoming: list[dict]) -> list[dict]:
    """Apply a batch of client changes. Returns per-change results."""
    # Attribute anything already pending in the vault to the workstation
    # before the push lands, so the pusher only gets credited its own edits.
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn, "_local")

    results = []
    touched: set[Path] = set()
    for ch in incoming:
        eid = ch.get("id")
        op = ch.get("op", "upsert")
        etype = ch.get("type")
        updated = ch.get("updated")
        d = ch.get("data") or {}
        if not eid or not etype or not updated:
            results.append({"id": eid, "status": "invalid"})
            continue

        current = changelog.latest_updated(sync_conn, eid)
        if changelog.is_tombstoned(sync_conn, eid) and op == "upsert":
            results.append({"id": eid, "status": "rejected_tombstone"})
            continue
        if current is not None and updated <= current and op == "upsert":
            results.append({"id": eid, "status": "stale"})
            continue

        d = {**d, "id": eid, "updated": updated}
        existing_rel = _state_file(sync_conn, eid)
        try:
            if op == "delete":
                path = vault_apply.delete(vault, etype, eid, existing_rel)
            elif etype in vault_apply.UPSERT:
                path = vault_apply.upsert(vault, etype, d, existing_rel)
            else:
                results.append({"id": eid, "status": "unknown_type"})
                continue
        except (KeyError, ValueError) as e:
            results.append({"id": eid, "status": "invalid", "error": str(e)})
            continue
        if path:
            touched.add(path)
        results.append({"id": eid, "status": "applied"})

    for path in touched:
        indexer.index_file(index_conn, vault, path)
    changelog.capture(index_conn, sync_conn, device_id)
    return results
