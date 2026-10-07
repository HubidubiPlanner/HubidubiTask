# The Harness on a computer at home

Run this on the computer that should answer the apps' Harness questions, for
example a home server. It needs Claude Code (`claude`), python3 and
openssl.

```sh
sh deploy/harness/install.sh           # install or update, then start (port 8765)
PORT=9000 sh deploy/harness/install.sh # another port
sh deploy/harness/install.sh --remove  # stop and remove; settings stay
```

It installs a service: a systemd user service on Linux (kept running without a
login through `loginctl enable-linger`), a LaunchAgent on macOS. At the end it
prints the IP and port to type in the apps: **Settings**, under **AI**, after
choosing **Agent**.

## A local model

Claude Code uses its own login on that computer unless
`~/.config/hubidubitask-harness/harness.env` points it elsewhere. The installer
writes that file with the lines commented out. For a model served on the same
computer by a server that speaks Anthropic's Messages API:

```
ANTHROPIC_BASE_URL=http://127.0.0.1:8080
ANTHROPIC_AUTH_TOKEN=local
ANTHROPIC_MODEL=glm-5.2
ANTHROPIC_DEFAULT_HAIKU_MODEL=glm-5.2
CLAUDE_CODE_SUBAGENT_MODEL=glm-5.2
```

Restart it after a change: `systemctl --user restart hubidubitask-harness`, or
on macOS `launchctl kickstart -k gui/$(id -u)/com.hubidubitask.harness`.

## Firewall

Omarchy turns ufw on, which blocks the port. Open it to the home network only:

```sh
sudo ufw allow from 192.168.0.0/16 to any port 8765 proto tcp
```

## Check it

From another computer at home:

```sh
curl -k https://<ip>:8765/v1/models
```

It should answer with `claude-code`. Logs: `journalctl --user -u
hubidubitask-harness -f` on Linux, `~/Library/Logs/hubidubitask-harness.log` on
macOS.
