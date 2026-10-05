# hubidubios-sync

Phase 2 of the hubidubios plan: the vault reachable from other devices over the
LAN. A cursor-based, entity-level change feed over pinned TLS — deliberately
not a CRUD API (see `PLAN.md`).

## API (`:5233`)

```
POST /v1/pair                     one-time token -> device token + id
GET  /v1/changes?since=<cursor>   entities changed since cursor
POST /v1/changes                  batch of client-side changes
GET  /v1/plugins                  installed plugin manifests (Phase 3)
GET  /v1/plugins/<name>/bundle    signed view bundle (Phase 5)
POST /v1/agent/ask                streams the agent loop as NDJSON (Phase 4)
```

- The cursor is a monotonic server-side sequence number, never a wall clock.
- Deletions are tombstones with 90-day retention.
- Server-side conflict rules: last-write-wins per entity by `updated`;
  a tombstone always beats an upsert (ids are never reused, so an upsert to a
  tombstoned id is by definition concurrent). Field-level merge is the
  client's job (Phase 5).
- The feed is derived by content-hash diffing the index against persistent
  `sync.db` state, so `hubidubios index rebuild` emits nothing spurious.

## Usage

```sh
hubidubios-sync serve --bind <lan-ip>    # refuses 0.0.0.0; TLS cert auto-generated
hubidubios-sync pair                     # one-time pairing token (5-minute window)
hubidubios-sync devices                  # list paired devices
```

On first `serve` a self-signed cert is generated under `~/vault/.hubidubios/`;
its sha256 fingerprint (printed at startup and by `pair`) is what clients pin.

`sync.db` (devices, change log, tombstones) is persistent per-device state —
unlike `index.db` it must not be deleted casually, and like all of `.hubidubios/`
it is never synced.

## Tests

```sh
uv run pytest tests/unit/test_changelog.py tests/integration
```

Target-host deployment (nftables, Avahi, Radicale, systemd units): `deploy/workstation/config/`.
