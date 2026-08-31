from prodos_vault import ids


def test_prefixes():
    assert ids.new_id("task").startswith("t_")
    assert ids.new_id("event").startswith("e_")
    assert ids.new_id("note").startswith("n_")
    assert ids.new_id("inbox_item").startswith("i_")


def test_uniqueness():
    generated = {ids.new_id("task") for _ in range(10_000)}
    assert len(generated) == 10_000


def test_valid_and_type():
    i = ids.new_id("note")
    assert ids.is_valid(i)
    assert ids.type_of(i) == "note"
    assert not ids.is_valid("t_UPPER")
    assert not ids.is_valid("x_abcd2345")
    assert ids.type_of("x_abcd2345") is None
