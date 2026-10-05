# Target-host configuration (Arch workstation)

Phase 2 configs, deployed on `hubidubios-workstation`. Untestable on the macOS dev
machine; verified on target against PLAN.md's Phase 2 acceptance:

- `curl` against `/v1/changes` with a valid device token returns entity JSON
  (covered cross-platform by `tests/integration/`).
- `avahi-browse -rt _hubidubios._tcp` resolves from another LAN device.
- A desktop calendar app creates an event; it appears in `hubidubios agenda`.
- `nmap` from outside the LAN subnet shows no open ports.

| File | Destination | Notes |
|---|---|---|
| `nftables.conf` | `/etc/nftables.conf` | set `lan` to the real subnet first |
| `avahi/hubidubios.service` | `/etc/avahi/services/` | fill `fp=` with the cert fingerprint |
| `radicale/config` | `/etc/radicale/config` | symlink collection-root to `~/vault/calendars` (header comment) |
| `systemd/hubidubios-sync.service` | `~/.config/systemd/user/` | set `--bind` to the LAN IP |
| `systemd/hubidubios-watch.service` | `~/.config/systemd/user/` | optional; sweep covers reads |
| `systemd/vault-autocommit.{service,timer}` | `~/.config/systemd/user/` | bare repo setup in header comment |

Syncthing: share `~/vault/` with laptops/desktops only — not the phone. Add
`.hubidubios` and `.Radicale.*` to the folder's ignore patterns (`.stignore`);
the index is per-device and never synced.
