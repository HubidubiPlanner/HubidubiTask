#!/bin/sh
# Install the HubidubiTask Harness on this computer (Linux or macOS) as a
# service that starts with it and answers the apps on PORT (default 8765).
#
#   sh deploy/harness/install.sh            install or update, then start
#   PORT=9000 sh deploy/harness/install.sh  another port
#   sh deploy/harness/install.sh --remove   stop it and remove the service
#
# Needs python3, openssl and Claude Code (`claude`) on this computer. Settings
# stay in ~/.config/hubidubitask-harness and survive updates and removal.
set -eu

PORT="${PORT:-8765}"
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../src/harness/hubidubitask_harness.py"
APP="$HOME/.local/share/hubidubitask-harness"
CONF="${XDG_CONFIG_HOME:-$HOME/.config}/hubidubitask-harness"
OS=$(uname -s)
UNIT="$HOME/.config/systemd/user/hubidubitask-harness.service"
PLIST="$HOME/Library/LaunchAgents/com.hubidubitask.harness.plist"

stop() {
  if [ "$OS" = Darwin ]; then
    launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
  else
    systemctl --user disable --now hubidubitask-harness.service 2>/dev/null || true
  fi
}

if [ "${1:-}" = "--remove" ]; then
  stop
  rm -f "$UNIT" "$PLIST"
  [ "$OS" = Darwin ] || systemctl --user daemon-reload 2>/dev/null || true
  rm -rf "$APP"
  echo "Removed. Settings are still in $CONF."
  exit 0
fi

# The system's python3 rather than whatever is first on PATH (a project's
# virtualenv can disappear). On macOS it is Apple's signed one, which the
# firewall lets accept connections without asking.
if /usr/bin/python3 -c 'import ssl' >/dev/null 2>&1; then PY=/usr/bin/python3
else PY=$(command -v python3) || { echo "python3 is needed." >&2; exit 1; }; fi
command -v openssl >/dev/null || { echo "openssl is needed (once, for the certificate)." >&2; exit 1; }
CLAUDE=$(command -v claude || ls "$HOME/.local/bin/claude" 2>/dev/null || true)
[ -n "$CLAUDE" ] || echo "Note: Claude Code is not installed yet; the apps will say so until it is." >&2

mkdir -p "$APP" "$CONF"
cp "$SRC" "$APP/hubidubitask_harness.py"
chmod 755 "$APP/hubidubitask_harness.py"

if [ ! -f "$CONF/harness.env" ]; then
  cat > "$CONF/harness.env" <<'ENV'
# Extra environment for Claude Code, one KEY=VALUE per line.
# Empty: Claude Code uses its own login on this computer (`claude` once to sign in).
#
# To answer with a model served on this network instead, point it at a server
# that speaks Anthropic's Messages API, for example:
#ANTHROPIC_BASE_URL=http://127.0.0.1:8080
#ANTHROPIC_AUTH_TOKEN=local
#ANTHROPIC_MODEL=glm-5.2
#ANTHROPIC_DEFAULT_HAIKU_MODEL=glm-5.2
#CLAUDE_CODE_SUBAGENT_MODEL=glm-5.2
ENV
fi

# A service starts with a short PATH; give it the places `claude` lives.
SVC_PATH="$HOME/.local/bin:$HOME/.claude/local:$HOME/.npm-global/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
stop
if [ "$OS" = Darwin ]; then
  mkdir -p "$(dirname "$PLIST")" "$HOME/Library/Logs"
  cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.hubidubitask.harness</string>
  <key>ProgramArguments</key>
  <array><string>$PY</string><string>$APP/hubidubitask_harness.py</string>
    <string>--port</string><string>$PORT</string><string>--config</string><string>$CONF</string></array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>$SVC_PATH</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/hubidubitask-harness.log</string>
</dict>
</plist>
PL
  launchctl bootstrap "gui/$(id -u)" "$PLIST"
  LOGS="tail -f ~/Library/Logs/hubidubitask-harness.log"
  IPS=$(for i in en0 en1 en2; do ipconfig getifaddr $i 2>/dev/null || true; done)
else
  mkdir -p "$(dirname "$UNIT")"
  cat > "$UNIT" <<UN
[Unit]
Description=HubidubiTask Harness (Claude Code for the HubidubiTask apps)
After=network-online.target

[Service]
ExecStart=$PY $APP/hubidubitask_harness.py --port $PORT --config $CONF
Environment=PATH=$SVC_PATH
Restart=on-failure

[Install]
WantedBy=default.target
UN
  systemctl --user daemon-reload
  systemctl --user enable --now hubidubitask-harness.service
  # Keep it running when no one is logged in.
  loginctl enable-linger "$USER" 2>/dev/null || echo "Note: it runs while you are logged in (loginctl enable-linger failed)." >&2
  LOGS="journalctl --user -u hubidubitask-harness -f"
  IPS=$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 || true)
fi

echo
echo "The Harness is running on port $PORT. In the app: Settings, Agent, Harness:"
for ip in $IPS; do echo "    IP $ip    Port $PORT"; done
echo "Logs: $LOGS"
if command -v ufw >/dev/null 2>&1; then
  echo
  echo "If ufw is on (Omarchy turns it on), let the home network reach the Harness:"
  echo "    sudo ufw allow from 192.168.0.0/16 to any port $PORT proto tcp"
  echo "    sudo ufw allow from 10.0.0.0/8 to any port $PORT proto tcp"
fi
[ "$OS" = Darwin ] && echo "If macOS asks whether python3 may accept incoming connections, allow it."
exit 0
