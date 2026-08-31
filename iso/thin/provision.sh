#!/usr/bin/env bash
# Provision a prodos-thin client (PLAN.md Phase 7).
#
# Do NOT build an Arch ARM ISO (community-maintained, often stale). Run this on
# a fresh Raspberry Pi OS Lite or Alpine aarch64 install. The thin client keeps
# no local state and runs no local inference — it SSHes into the workstation.
# It needs only: network, SSH, mosh, tmux, and optionally Moonlight for desktop
# streaming from the workstation.
#
#   sudo ./provision.sh <workstation-host> [user]
set -euo pipefail

WORKSTATION="${1:?usage: provision.sh <workstation-host> [user]}"
USER_NAME="${2:-prodos}"

if command -v apk >/dev/null 2>&1; then
  # Alpine
  apk update
  apk add openssh-client mosh tmux avahi avahi-tools
elif command -v apt-get >/dev/null 2>&1; then
  # Raspberry Pi OS Lite / Debian
  apt-get update
  apt-get install -y openssh-client mosh tmux avahi-utils
else
  echo "unsupported base image (need apk or apt-get)" >&2
  exit 1
fi

# A tmux-first login that drops the user straight into a session on the
# workstation over mosh. Discovery via mDNS with a manual-host fallback.
install -Dm755 /dev/stdin "/home/$USER_NAME/.local/bin/prodos-connect" <<EOF
#!/usr/bin/env bash
host="\${1:-$WORKSTATION}"
exec mosh "\$host" -- tmux new-session -A -s prodos
EOF

cat > "/home/$USER_NAME/.bash_profile" <<'EOF'
# Thin client: connect to the workstation on login (Ctrl-C to stay local).
[ -z "$SSH_TTY" ] || exec prodos-connect
EOF

echo "prodos-thin provisioned. On login it connects to $WORKSTATION via mosh+tmux."
echo "Optional desktop streaming: install moonlight-qt and pair with the workstation's GameStream/Sunshine host."
