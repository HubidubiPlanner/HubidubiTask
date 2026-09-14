"""An inbox capture can be a photo or voice memo, not just typed text.

"Task or note?" is asked on the phone at capture time, so a photo the user calls
a *task* must land in the inbox with its attachment — not silently become a note.
The bytes live in the vault as a file; the inbox line only references them, so
the inbox still reads as a plain list in any editor.
"""

from hubidubios_vault.entities import InboxItem
from hubidubios_vault.formats import inbox_md


def test_text_capture_line_is_unchanged():
    """The common case must not grow metadata it does not need."""
    line = inbox_md.format_item_line("call the dentist", "09:14", "i_x9y8")

    assert line == "- 09:14 call the dentist  <!-- id:i_x9y8 -->"


def test_media_capture_round_trips(tmp_path):
    line = inbox_md.format_item_line(
        "Whiteboard from standup", "09:14", "i_x9y8",
        kind="image", media="inbox/media/i_x9y8.jpg",
        media_sha256="a" * 64, media_mime="image/jpeg", media_bytes=15,
    )
    parsed = inbox_md.parse_file(line + "\n", tmp_path / "2026-09-14.md", "2026-09-14T00:00:00Z")

    assert len(parsed) == 1
    item, _ = parsed[0]
    assert isinstance(item, InboxItem)
    assert item.text == "Whiteboard from standup"
    assert item.kind == "image"
    assert item.media == "inbox/media/i_x9y8.jpg"
    assert item.media_sha256 == "a" * 64
    assert item.media_mime == "image/jpeg"
    assert item.media_bytes == 15


def test_media_metadata_is_hidden_from_the_readable_text(tmp_path):
    """The attachment must not leak into the item text the user reads."""
    line = inbox_md.format_item_line(
        "Voice memo", "09:14", "i_x9y8", kind="audio",
        media="inbox/media/i_x9y8.m4a", media_sha256="b" * 64,
        media_mime="audio/mp4", media_bytes=42,
    )
    (item, _), = inbox_md.parse_file(line + "\n", tmp_path / "2026-09-14.md",
                                     "2026-09-14T00:00:00Z")

    assert item.text == "Voice memo", "media metadata leaked into the text"


def test_plain_items_default_to_text_kind(tmp_path):
    """Items captured before media existed must still parse."""
    (item, _), = inbox_md.parse_file(
        "- 09:14 old item  <!-- id:i_old1 -->\n",
        tmp_path / "2026-09-14.md", "2026-09-14T00:00:00Z")

    assert item.kind == "text"
    assert item.media is None


def test_corrupt_byte_count_does_not_break_parsing(tmp_path):
    """A hand-edited line must not take the whole inbox down."""
    (item, _), = inbox_md.parse_file(
        "- 09:14 x  <!-- id:i_x9y8 kind:image bytes:notanumber -->\n",
        tmp_path / "2026-09-14.md", "2026-09-14T00:00:00Z")

    assert item.media_bytes is None
