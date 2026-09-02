# prodos-agent

Phase 4 of the prodos plan: natural-language control of the vault. The model
plans narrowly, code executes.

## The loop (deliberately constrained)

1. System prompt lists available skills with descriptions and parameter
   schemas — the built-in `vault-query` plus every installed plugin's skills,
   so the tool set changes as plugins are installed.
2. The model returns **one** skill call with structured params, or a final
   answer. No parallel calls, no free-form code.
3. The params are validated against the skill's JSON Schema; on failure the
   validation error is fed back and the model retries, capped at 3 attempts.
4. The skill executes in its bwrap sandbox; the result is fed back.
5. The loop caps at 5 turns, then forces a final answer.

Every skill invocation (params and result) is logged to
`~/.local/state/prodos/agent.log`.

## Context

The vault is never dumped into the prompt. The agent calls `vault-query`,
which searches the index and returns ranked snippets capped at a fixed token
budget, truncated oldest/least-relevant first.

## Inference

Talks to any **OpenAI-compatible** endpoint ("a wire format, not a
destination"). The default is the user's local **selfhostedai-agent** OpenAI server
(`http://127.0.0.1:8081/v1`, plaintext + loopback-only, optional bearer key);
`llama.cpp` on :8080 is a documented fallback. The client is pluggable —
`ScriptedClient` drives the loop in tests, so everything around inference is
developed and verified without a live model.

Endpoint resolution: `$PRODOS_MODEL_ENDPOINT` → `[inference]` in the vault's
`.prodos/config.toml` → selfhostedai-agent default. Config shape:

```toml
[inference]
endpoint = "http://127.0.0.1:8081/v1/chat/completions"
api_key = ""              # optional bearer
tls_fingerprint = ""      # optional sha256:... pin for non-loopback https
model = "local"
```

## Interfaces

```sh
prodos-agent status [--json]                       # inference connection status
prodos-agent ask "what's on my plate this week?"   # one-shot
prodos-agent chat                                  # TUI, tmux/SSH friendly
```

`status` probes `GET /v1/models` and reports connected/disconnected + the served
models — the one source of truth behind the desktop bar indicator and the
mobile status chip. `ask`/`chat` print an "inference offline" banner instead of
failing opaquely.

Plus `POST /v1/agent/ask` on prodos-sync: streams the loop as NDJSON, one
self-contained JSON event per line. The phone can lose the stream mid-response
without corrupting local state — nothing is committed client-side until the
terminal `answer` event, and read-only queries touch no files.

## Tests

```sh
uv run pytest tests/unit/test_agent.py tests/integration/test_agent_endpoint.py
```

## Acceptance (PLAN.md Phase 4)

The four acceptance queries ("what's on my plate", "move the design review to
Thursday", "summarize my notes on the sandbox design", all offline) require a
real model and are validated on the workstation. The loop mechanics they
depend on — tool dispatch, schema-retry, context grounding, offline operation
(no code path reaches the network except the model client) — are covered here
with scripted completions.
