# hubidubios-harness

Claude Code on the workstation, served to your own phone and Mac. In the apps
it is the **Harness** agent: chat, item reviews and feeds can use it, and
it runs on the Claude plan of the person logged in to Claude Code on that
machine.

## Set up (once, on the workstation)

```sh
claude                                        # log in to Claude Code, then quit
systemctl --user enable --now hubidubios-harness
hubidubios-harness show                       # address, token, fingerprint
```

Type the address and token into the app: **Settings → Agent → Harness**.
On the phone the address is filled in from the paired workstation.

## What it does, and what it can't

Each question becomes one `claude -p` call with **every tool switched off**
(`--tools ""`), in an empty folder, with no session kept. It can only write
text back: a feed's page text cannot make it run commands or read files.

The server listens on the LAN address only (never 0.0.0.0), on port 5240,
over TLS with its own self-signed certificate, and answers only requests
carrying its token. `hubidubios-harness-firewall.service` opens 5240 in
Omarchy's ufw to private networks only.

It speaks the OpenAI chat-completions shape (`GET /v1/models`,
`POST /v1/chat/completions`), so anything that talks to a local model can talk
to it. `HUBIDUBIOS_HARNESS_COMMAND` replaces the `claude` command line, for
another harness or for tests.

Token, certificate and key live in `~/.config/hubidubios-harness/`. Delete the
token file and restart the service to issue a new one.
