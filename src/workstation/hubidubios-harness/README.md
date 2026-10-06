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

## What it can use

Each question becomes one `claude -p` call in `--restricted` mode, with no
session kept, no user or project settings, and only our MCP server. What it
may touch depends on the request:

| Request | Tools | Runs in |
|---|---|---|
| Chat and item reviews (`"hubidubios_tools": true`) | Read, Glob, Grep, WebFetch, Task (subagents), and the vault's skills over MCP: vault-query, gtd-next, agenda, gtd-clarify, gtd-file, gtd-archive, gtd-weekly-review | `~/vault` |
| Feeds, and any request that doesn't ask | none: text only | an empty folder |

Nothing can run commands or code: there is no Bash, no Write or Edit, and
anything outside the list is refused, since no one is there to approve it.
Read, Glob and Grep stay inside the vault. The vault skills come from
`hubidubios-mcp` (in hubidubios-agent) and run in the same bubblewrap sandbox
as the workstation agent's own calls. Feeds never get tools because their
text comes from strangers' web pages.

`HUBIDUBIOS_VAULT` moves the vault; `HUBIDUBIOS_HARNESS_MCP` replaces the MCP
server's command line.

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
