"""A model's visible thinking is not its answer.

Some inference servers wrap the model in a reasoning persona, so replies arrive
as a <think> monologue followed by the real answer. Shown to the user that
monologue is noise; fed to the action parser it is why nothing parses and the
user is told the model "couldn't produce a valid response".
"""

from hubidubios_agent.loop import _parse_action, strip_reasoning


def test_a_closed_block_is_removed():
    assert strip_reasoning("<think>weighing it up</think>\n\nThe quote covers gutters.") \
        == "The quote covers gutters."


def test_an_unterminated_block_has_no_answer_in_it():
    """Measured against the live server: a short note produced 10,477 characters
    of an unterminated <think>, all of it the model talking to itself. Keeping
    what follows the tag — as this used to — hands the user that monologue and
    calls it a review. There is nothing in there to salvage."""
    assert strip_reasoning("<think>still going, the answer is 4") == ""


def test_an_answer_before_the_thinking_survives():
    assert strip_reasoning("The quote covers gutters. <think>now, was that right") \
        == "The quote covers gutters."


def test_other_reasoning_tags_are_handled():
    assert strip_reasoning("<reasoning>hmm</reasoning>done") == "done"
    assert strip_reasoning("<Thinking>hmm</Thinking>done") == "done"


def test_plain_text_is_untouched():
    assert strip_reasoning("just an answer") == "just an answer"


def test_json_hidden_behind_reasoning_now_parses():
    """The whole point: the action was there, the monologue hid it."""
    assert _parse_action('<think>which tool?</think>{"answer": "hi"}') == {"answer": "hi"}


def test_a_reply_that_is_only_reasoning_yields_nothing_to_parse():
    assert _parse_action("<think>no idea</think>") is None


class _ProseOnlyClient:
    """A model that cannot emit the JSON contract — reasoning-wrapped models
    routinely cannot — but answers perfectly well in prose."""

    def __init__(self):
        self.json_modes: list[bool] = []

    def complete(self, messages, json_mode=True):
        self.json_modes.append(json_mode)
        if json_mode:
            return "<think>the user wants to know about roofs</think>"
        return "A roof leak needs a roofer, and soon."

    def health(self):
        return {"connected": True, "server": "test", "models": ["test"]}


def _agent(client):
    from unittest.mock import MagicMock

    from hubidubios_agent.loop import Agent

    agent = Agent.__new__(Agent)   # no registry, no sandbox: only the loop here
    agent.client = client
    agent.log_file = None
    agent.system_prompt = "system"
    agent.skills = {}
    agent.vault = MagicMock()
    agent.conn = MagicMock()
    return agent


def test_a_model_that_cannot_do_json_still_answers():
    client = _ProseOnlyClient()

    events = list(_agent(client).run_streamed("what about the roof?"))

    answer = next(e for e in events if e["event"] == "answer")
    assert answer["answer"] == "A roof leak needs a roofer, and soon."
    assert answer["plain_fallback"] is True
    # The retry loop asked for JSON, and only the fallback dropped it.
    assert client.json_modes[-1] is False


def test_the_fallback_says_so_when_the_model_only_reasons():
    class _Hopeless(_ProseOnlyClient):
        def complete(self, messages, json_mode=True):
            return "<think>still thinking"

    answer = next(e for e in _agent(_Hopeless()).run_streamed("q")
                  if e["event"] == "answer")
    assert "never answered" in answer["answer"]
