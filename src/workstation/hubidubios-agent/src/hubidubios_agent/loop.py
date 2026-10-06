"""The agent loop. Deliberately constrained (PLAN.md Phase 4):

1. System prompt lists available skills with descriptions and schemas.
2. Model returns ONE skill call with structured params, or a final answer.
3. Validate against the schema. On failure, return the validation error and
   retry, capped at 3 attempts.
4. Execute in sandbox, feed the result back.
5. Cap the loop at 5 turns, then force a final answer.

No parallel tool calls. No free-form code generation.

Emits a stream of events (dicts). `run()` collects them; `run_streamed()`
yields them as they happen for the phone endpoint and the TUI.
"""

import json
import re

import jsonschema

from . import MAX_SCHEMA_RETRIES, MAX_TURNS
from .client import ModelError
from .context import run_vault_query
from .log import log_event
from .prompt import build_system_prompt, skill_index

_THINK_RE = re.compile(r"<(think|thinking|reasoning)>.*?</\1>", re.S | re.I)


def strip_reasoning(text: str) -> str:
    """Drop a model's visible chain-of-thought.

    Some servers wrap the model in a reasoning persona, so the reply arrives as
    a <think> monologue followed by the actual answer. The monologue is not the
    answer: shown to the user it is noise, and fed to a JSON parser it is the
    reason nothing parses.
    """
    out = _THINK_RE.sub("", text)
    # An unterminated block means the model spent its whole budget thinking and
    # never reached an answer. What follows the opening tag is the monologue
    # itself, so keeping it hands the user 10,000 characters of the model
    # talking to itself; there is no answer in there to salvage.
    if "<think" in out.lower():
        return out[:out.lower().index("<think")].strip()
    return out.strip()


def _parse_action(text: str) -> dict | None:
    """Extract the single JSON object the model was told to emit. Tolerates
    prose or code fences around it (local models leak both)."""
    text = strip_reasoning(text)
    if "```" in text:
        # take the first fenced block's contents
        parts = text.split("```")
        for chunk in parts:
            chunk = chunk.lstrip()
            if chunk.startswith("json"):
                chunk = chunk[4:]
            chunk = chunk.strip()
            if chunk.startswith("{"):
                text = chunk
                break
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end < start:
        return None
    try:
        obj = json.loads(text[start:end + 1])
        return obj if isinstance(obj, dict) else None
    except ValueError:
        return None


class Agent:
    def __init__(self, vault, index_conn, registry, client,
                 sandbox_runner=None, log_file=None):
        self.vault = vault
        self.conn = index_conn
        self.registry = registry
        self.client = client
        self.log_file = log_file
        # Injectable so tests need no bwrap; defaults to the real sandbox.
        if sandbox_runner is None:
            from hubidubios_plugin_host.sandbox import run_skill
            sandbox_runner = run_skill
        self._run_skill = sandbox_runner
        self.system_prompt = build_system_prompt(registry)
        self.skills = skill_index(registry)

    def _execute(self, name: str, params: dict) -> dict:
        entry = self.skills[name]
        if entry["plugin"] is None:  # vault-query, built-in
            return run_vault_query(self.conn, params)
        return self._run_skill(self.vault, entry["plugin"], entry["skill"], params)

    def run_streamed(self, user_message: str):
        messages = [
            {"role": "system", "content": self.system_prompt},
            {"role": "user", "content": user_message},
        ]
        log_event({"event": "ask", "message": user_message}, self.log_file)

        for turn in range(MAX_TURNS):
            action = self._get_valid_action(messages)

            if action is None:
                # The model could not produce the JSON contract the tool loop
                # needs — a reasoning-wrapped model often cannot, and telling
                # the user "I couldn't produce a valid response" for a question
                # it can answer in prose is a refusal it never intended. Answer
                # in prose instead, and say it had no tools.
                answer = self._plain_answer(user_message)
                log_event({"event": "answer", "answer": answer, "plain_fallback": True},
                          self.log_file)
                yield {"event": "answer", "answer": answer, "plain_fallback": True}
                return
            if "answer" in action:
                yield {"event": "answer", "answer": str(action["answer"])}
                log_event({"event": "answer", "answer": str(action["answer"])},
                          self.log_file)
                return

            name = action["tool"]
            params = action.get("params", {})
            yield {"event": "tool_call", "tool": name, "params": params}
            try:
                result = self._execute(name, params)
                status = "ok"
            except Exception as e:  # skill/sandbox failures are fed back, not fatal
                result = {"error": str(e)}
                status = "error"
            log_event({"event": "tool_result", "tool": name, "params": params,
                       "status": status, "result": result}, self.log_file)
            yield {"event": "tool_result", "tool": name, "status": status,
                   "result": result}

            messages.append({"role": "assistant",
                             "content": json.dumps({"tool": name, "params": params})})
            messages.append({"role": "user",
                             "content": f"tool_result for {name}: "
                                        f"{json.dumps(result)}"})

        # Loop exhausted without an answer: force one from the accumulated context.
        forced = self._force_answer(messages)
        yield {"event": "answer", "answer": forced, "forced": True}
        log_event({"event": "answer", "answer": forced, "forced": True},
                  self.log_file)

    def _get_valid_action(self, messages) -> dict | None:
        """Ask the model and validate. Retries on schema/parse failure up to
        MAX_SCHEMA_RETRIES, feeding the error back each time."""
        working = list(messages)
        for attempt in range(MAX_SCHEMA_RETRIES):
            try:
                raw = self.client.complete(working)
            except ModelError:
                raise
            if not raw.strip():
                # The model ended its turn without saying anything (selfhostedai logs
                # "EOG token generated at position 0"). Asking the same question
                # twice more produces the same nothing, three times the work,
                # and three more sessions on the server.
                log_event({"event": "empty_completion", "attempt": attempt + 1},
                          self.log_file)
                return None
            action = _parse_action(raw)
            err = self._validate(action)
            if err is None:
                return action
            log_event({"event": "invalid_action", "attempt": attempt + 1,
                       "error": err, "raw": raw[:500]}, self.log_file)
            working = working + [
                {"role": "assistant", "content": raw},
                {"role": "user",
                 "content": f"That was invalid: {err}. Respond with a single "
                            "valid JSON object as instructed."},
            ]
        return None

    def _validate(self, action: dict | None) -> str | None:
        """Return an error string, or None if the action is valid."""
        if action is None:
            return "could not parse a JSON object"
        if "answer" in action:
            return None
        if "tool" not in action:
            return 'must contain "tool" or "answer"'
        name = action["tool"]
        if name not in self.skills:
            return f"unknown tool {name!r}"
        params = action.get("params", {})
        if not isinstance(params, dict):
            return '"params" must be an object'
        try:
            jsonschema.validate(params, self.skills[name]["schema"])
        except jsonschema.ValidationError as e:
            return f"params invalid: {e.message}"
        return None

    def _plain_answer(self, user_message: str) -> str:
        """One completion with no JSON contract and no tools.

        The fallback when the tool loop cannot get a valid action out of the
        model. It answers from what the model knows rather than from the vault,
        which is a smaller answer than the loop would have given — and a far
        larger one than an apology.
        """
        system = ("You are the user's assistant. Answer in plain prose. Be "
                  "concise and concrete. Do not use JSON, markdown fences, or "
                  "tool calls.")
        try:
            raw = self.client.complete(
                [{"role": "system", "content": system},
                 {"role": "user", "content": user_message}], json_mode=False)
        except ModelError as e:
            return f"The model could not be reached: {e}"
        answer = strip_reasoning(raw).strip()
        if not answer:
            return ("The model spent its whole reply reasoning and never "
                    "answered. Ask again, or ask something shorter.")
        return answer

    def _force_answer(self, messages) -> str:
        working = messages + [{
            "role": "user",
            "content": "Stop using tools. Give your best final answer now as "
                       '{"answer": "..."}.'}]
        try:
            action = _parse_action(self.client.complete(working))
        except ModelError:
            action = None
        if action and "answer" in action:
            return str(action["answer"])
        return "I wasn't able to complete that within the turn limit."

    def run(self, user_message: str) -> dict:
        """Collect the stream into a final result with the tool trace."""
        events = list(self.run_streamed(user_message))
        answer = next((e["answer"] for e in reversed(events)
                       if e["event"] == "answer"), "")
        return {"answer": answer, "events": events}
