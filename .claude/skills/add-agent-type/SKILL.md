---
name: add-agent-type
description: Add a new AI choice to HubidubiTask's Settings › AI Agent. That is either a hosted AI service reached with the user's API key (Mistral, Gemini, OpenRouter, Groq, any OpenAI-compatible API) or another kind of agent the user runs on a computer at home and HubidubiTask finds by its address (Ollama, LM Studio, goose, Qwen Code, llama.cpp server and so on). Use this whenever someone wants HubidubiTask to talk to, support or plug into another AI model, provider, local LLM or agent, even if they don't say "agent type".
---

# Add an AI agent type

The AI chosen in Settings answers reviews of items, and the user's own
agent at home may also change items when asked. There are two ways to add
one. Pick the right one first.

| It is… | Add… | Example |
|---|---|---|
| A service on the internet with one address, reached with the user's API key | an **`AgentType`** (a new choice in the dropdown) | Mistral, Gemini, OpenRouter |
| Something the user runs on a computer at home, found by typing its IP | an **`AgentKind`** (found behind the existing "Agent" choice, no new dropdown entry) | Ollama, LM Studio |

Paths below are under `src/apps/shared/src/main/kotlin/com/hubidubitask/core/agent/`
unless they start with `src/apps/` or `android/` / `desktop/`.

## Rules that never bend

- **One TLS policy.** Every client except Claude's SDK makes its HTTP client with `Agent.http(endpoint, config.tlsFingerprint, …)`:
  - a public host needs a certificate an authority vouches for
  - a home server is pinned on first use
  - plain `http://` only works inside the home network

  `AgentHttpTest` builds every registered type's (and every home kind's) client against a public `http://` address and fails if any of them connects some other way. Never set `sdk = true`: only Claude is reached through an SDK.
- **Feeds never use these.** A feed's text is a stranger's, and `FeedRunner` always asks Claude. Don't route feeds anywhere else.
- **Secrets go to the system's secret store, never logs.** `AgentStore` keeps the field `apiKey` (or `token`) in the Android Keystore or the Keychain/Secret Service. Don't print keys, and don't put them in URLs.
- **The user's items are data.** The home agent gets them fenced by `ItemsContext`, and its changes are checked by `AgentActions`. Don't bypass either.
- **Ids never change once shipped.** They are stored: `harness` stays `harness`.

## A. A hosted service: a new `AgentType`

How it fits: `AgentType.kt` holds the interface and `AgentTypes.all`, the dropdown's order. Claude stays first, as the default. `types/ClaudeAgentType.kt`, `types/OpenAiAgentType.kt` and `types/HomeAgentType.kt` are the examples. `AgentStore.kt` keeps each type's settings: a new type's go under `agent_<id>_…` with nothing to register. `android/…/ui/AgentSection.kt` and `desktop/…/ui/AgentSection.kt` draw the fields from the type, so there is no UI code to write.

1. Check the service's API documentation. If it speaks the OpenAI chat shape (`POST …/v1/chat/completions`, `GET …/v1/models`, a Bearer key), reuse `OpenAiClient`. Most do.
2. Write `types/<Name>AgentType.kt` as an `object <Name>AgentType : AgentType`:
   - `id`: lowercase, permanent.
   - `label`: the brand as it writes itself, a plain string.
   - `endpoint`: its fixed base address, e.g. `"https://api.mistral.ai"`. `OpenAiClient` adds `/v1`.
   - `fields`: `listOf(AgentField("apiKey", "<what a key looks like, e.g. its prefix>…", secret = true))`. A placeholder that's a sentence goes through `t()` as a getter. Use only the keys `endpoint` and `apiKey`; other keys are not stored.
   - `defaultModel`: the model to ask for (blank lets the server pick its first, which is rarely right for a hosted API).
   - `ready(values)`: the key is not blank.
   - `client(config) = OpenAiClient(config)`.

   A service that doesn't speak the OpenAI shape gets its own `<Name>Client : ModelClient`, with `complete(messages)`, `health()` and `close()`. Build the OkHttp client with `Agent.http(...)` exactly as `OpenAiClient` does, and nothing else.
3. Add it to `AgentTypes.all`, after Claude.
4. Update the tests that list every type: `ClaudeClientTest` ("Settings offers …") and `AgentStoreTest` ("every type has its own id…"). Add a test of the client against `Stub` (see `OpenAiClient`'s uses) if you wrote your own client.

## B. An agent at home: a new `AgentKind`

How it fits:
- `HomeAgent.kt` holds `enum class AgentKind(id, label, port, user, signature)`. The order of entries is the order its default ports are tried when the user types no port. **`OPENAI` stays last**: it's any OpenAI-shaped server, the catch-all.
- `AgentProbe.kt` finds what listens at an address **without sending the token**:
  - opencode/Kilo first
  - then OpenClaw/Hermes by `/health`
  - then every kind's `signature`
  - then `/v1/models`

  The token goes only to the kind found, and over TLS only to the certificate first seen there.

1. Find a path the agent answers **without a token**, with something only it says. Ollama, for example, answers `GET /api/tags` with `{"models":[…]}`. That becomes its `Signature(path) { json -> … }`. Match on a field it alone has; a bare 200 is not enough.
2. Add the entry before `OPENAI`:
   ```kotlin
   OLLAMA("ollama", "Ollama", 11434, null, Signature("/api/tags") { it?.optJSONArray("models") != null }),
   ```
   - `user` is non-null only when it takes a password as Basic auth with a fixed user name; otherwise the token goes as Bearer.
   - `port` is its usual port, or null if it has none.
3. If it speaks the OpenAI chat shape at `/v1`, that's all: `HomeAgent.clientFor` asks it through `OpenAiAgent`, and the probe picks the first model `/v1/models` lists. Otherwise add a `HomeAgentClient` (see `OpencodeAgent.kt`) and a branch in `HomeAgent.clientFor`. Its HTTP client comes from `Agent.http`.
4. Test in `src/apps/shared/src/test/kotlin/com/hubidubitask/core/agent/AgentProbeTest.kt` with a `stub { c -> when (c.path) { … } }` that answers like the real agent. Cover:
   - it is found, with its kind, label and model
   - a plain OpenAI-shaped server is **not** taken for it
   - it is not taken for OpenClaw or Hermes, and they are not taken for it

   If it can require a token, also check that no token is sent before it is recognised: look at `stub.calls`.
5. Its default port joins `AgentKind.DEFAULT_PORTS` on its own, which is the list tried when no port is typed. Nothing to do there.

## Both ways

- **Translations.** Every new `t("…")` text needs Polish, German, French and Spanish. Write them to a JSON file, then run `python3 src/apps/tools/i18n_add.py that.json` from the repo root. Brand names stay plain, without `t()`.
- **Docs.** Add the new choice or kind to the AI section of `docs/MANUAL.md`: the choices table, or the home agent table with its port and how to make it listen on the network (Ollama, for instance, needs `OLLAMA_HOST=0.0.0.0`). Add a bullet under `## Unreleased` in `docs/CHANGELOG.md`.
- **Run the checks** from `src/apps`:
  ```
  ./gradlew :shared:test :desktop:test :android:assembleDebug
  ```
  If an Android emulator is running, install
  `android/build/outputs/apk/debug/android-debug.apk` and look at
  Settings › AI Agent. The emulator reaches the computer it runs on at `10.0.2.2`.

## Done when

- [ ] A: one file in `types/` and one line in `AgentTypes.all`; or B: one `AgentKind` entry with a token-free `Signature`, before `OPENAI`.
- [ ] Every HTTP client made by `Agent.http`. `AgentHttpTest` passes untouched. Nothing new in feeds.
- [ ] Keys only in the fields `apiKey`/`token`, never logged.
- [ ] Every shown text through `t()`, with all four translations.
- [ ] `./gradlew :shared:test :desktop:test :android:assembleDebug` passes.
- [ ] MANUAL and CHANGELOG updated. Commits signed off (`git commit -s`).
