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


def test_an_unterminated_block_keeps_what_follows():
    """A truncated reply must not come back empty — that reads as a dead model."""
    assert strip_reasoning("<think>still going, the answer is 42") == \
        "still going, the answer is 42"


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
