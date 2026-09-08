#!/usr/bin/env bash
# Runs INSIDE the Arch container (see run.sh). Exercises the whole install:
# build the packages, install them with pacman, verify the CLIs, run the full
# test suite (including the bwrap sandbox-escape tests that cannot run on
# macOS), then a live hubidubios-sync pair/push/pull + hubidubios-agent status smoke.
#
# The repo is mounted read-only at /src; we copy to /work so package builds
# never dirty the host tree.
set -euo pipefail

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    ok: %s\033[0m\n' "$*"; }

step "Copy repo to a writable workdir (only what the install test needs)"
mkdir -p /work-repo
cp -a /src/packages /src/plugins /src/tests /src/config /work-repo/
[ -f /src/pyproject.toml ] && cp /src/pyproject.toml /work-repo/
cd /work-repo
chown -R builder /work-repo

step "Build hubidubios packages (makepkg)"
PKG_DIRS=(
  packages/hubidubios-vault packages/hubidubios-plugin-host
  packages/hubidubios-sync packages/hubidubios-agent
  plugins/gtd plugins/calendar
)
for p in "${PKG_DIRS[@]}"; do
  ( cd "$p" && sudo -u builder makepkg -f --nodeps --skipinteg >/dev/null )
  ok "built $p"
done

step "Install via pacman -U (the real install path)"
pacman -U --noconfirm --overwrite '*' \
  packages/hubidubios-vault/*.pkg.tar.* \
  packages/hubidubios-plugin-host/*.pkg.tar.* \
  packages/hubidubios-sync/*.pkg.tar.* \
  packages/hubidubios-agent/*.pkg.tar.* \
  plugins/gtd/*.pkg.tar.* \
  plugins/calendar/*.pkg.tar.* >/dev/null
ok "packages installed"

step "CLI smoke (installed entry points on PATH)"
hubidubios --help >/dev/null           && ok "hubidubios"
hubidubios-sync --help >/dev/null      && ok "hubidubios-sync"
hubidubios-agent --help >/dev/null     && ok "hubidubios-agent"
hubidubios-plugins --help >/dev/null   && ok "hubidubios-plugins"

step "Full test suite against the INSTALLED packages (incl. bwrap escapes)"
# The installed packages are importable; run the repo's tests with system
# pytest. bubblewrap is present, so tests/sandbox actually executes here.
python -m pytest tests -q

step "Live install smoke: vault + plugins + sync + agent"
export HUBIDUBIOS_VAULT=/root/vault
export HUBIDUBIOS_PLUGIN_DIRS=/work-repo/plugins
python - <<'PY'
import os
from pathlib import Path
from hubidubios_vault.vault import Vault
from hubidubios_plugin_host.registry import discover, export_entity_types
v = Vault(os.environ["HUBIDUBIOS_VAULT"]); v.ensure_layout()
reg = discover([Path("/work-repo/plugins")], names={"gtd", "calendar"})
export_entity_types(v, reg)
print("    ok: registered", ", ".join(sorted(reg.entity_types())))
PY

hubidubios capture "hello from the container" >/dev/null && ok "capture"
hubidubios inbox --json | python -c 'import json,sys; assert len(json.load(sys.stdin))==1' && ok "inbox --json"

step "A real skill under bwrap (not dev-mode)"
hubidubios-plugins run-skill gtd gtd-weekly-review '{}' >/dev/null && ok "gtd-weekly-review under bwrap"

step "Agent status against a stub OpenAI server"
python - <<'PY' &
import json, http.server
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def do_GET(self):
        b=json.dumps({"object":"list","data":[{"id":"stub-30b"}]}).encode()
        self.send_response(200); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
http.server.HTTPServer(("127.0.0.1",8081),H).serve_forever()
PY
STUB=$!
sleep 1
hubidubios-agent status && ok "hubidubios-agent status (connected)"
kill "$STUB" 2>/dev/null || true

step "Live sync: serve, pair, push, pull"
hubidubios-sync serve --bind 127.0.0.1 --port 5233 --no-tls >/tmp/sync.log 2>&1 &
SYNC=$!
sleep 1
TOKEN=$(hubidubios-sync pair | head -1 | awk '{print $NF}')
RESP=$(curl -s http://127.0.0.1:5233/v1/pair -d "{\"token\":\"$TOKEN\",\"device_name\":\"ci\"}")
DEV=$(printf '%s' "$RESP" | python -c 'import json,sys; print(json.load(sys.stdin)["device_token"])')
# push a task from the "phone"
curl -s -H "Authorization: Bearer $DEV" http://127.0.0.1:5233/v1/changes \
  -d '{"changes":[{"id":"t_ci00001","type":"task","op":"upsert","updated":"2030-01-01T00:00:00Z","data":{"text":"pushed in ci","project":"ci","done":false}}]}' >/dev/null
# pull it back
COUNT=$(curl -s -H "Authorization: Bearer $DEV" "http://127.0.0.1:5233/v1/changes?since=0" \
  | python -c 'import json,sys; print(len(json.load(sys.stdin)["changes"]))')
[ "$COUNT" -ge 1 ] && ok "sync pair + push + pull ($COUNT changes)"
test -f "$HUBIDUBIOS_VAULT/tasks/ci.md" && ok "pushed task landed in the vault"
kill "$SYNC" 2>/dev/null || true

printf '\n\033[1;32m==> ALL CONTAINER CHECKS PASSED\033[0m\n'
