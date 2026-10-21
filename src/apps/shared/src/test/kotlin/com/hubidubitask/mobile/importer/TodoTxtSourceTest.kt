package com.hubidubitask.mobile.importer

import com.hubidubitask.core.ItemWriter
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.mobile.importer.sources.TodoTxtSource
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TodoTxtSourceTest {
    @BeforeTest fun english() { I18n.language = Language.EN }

    private fun fixture(): ImportValue.File {
        val bytes = javaClass.getResourceAsStream("/import/todotxt/todo.txt")!!.use { it.readBytes() }
        return ImportValue.File("todo.txt", bytes)
    }

    private fun file(text: String) = ImportValue.File("todo.txt", text.toByteArray())

    private fun read(value: ImportValue, sink: ImportSourcesTest.Sink = ImportSourcesTest.Sink()) =
        TodoTxtSource.read(value, ImportContext(sink))

    @Test fun `it is offered as a file, in the registry`() {
        assertSame(TodoTxtSource, ImportSources.of("todotxt"))
        assertTrue(TodoTxtSource.input is ImportInput.File)
    }

    @Test fun `open tasks come in with their first project and their due date`() {
        val p = read(fixture())
        assertEquals(listOf("Home", "Garden"), p.newProjects)
        val byText = p.items.associateBy { it.text }
        assertEquals(12, p.items.size, p.items.joinToString("\n"))

        val plumber = byText.getValue("Call the plumber about the boiler")
        assertEquals("Home", plumber.project)
        assertEquals("2026-10-24", plumber.due)
        assertEquals("Garden", byText.getValue("Plant tulip bulbs").project)
        assertEquals("2026-11-02", byText.getValue("Plant tulip bulbs").due)
        // +garden and +Garden are one project, spelled as it first appears.
        assertEquals("Garden", byText.getValue("Water the plants").project)
        assertEquals("2026-10-22", byText.getValue("Water the plants").due)
        assertEquals("Garden", byText.getValue("Pick up seeds").project)
        assertNull(byText.getValue("Renew library card").project)
        assertNull(byText.getValue("Buy stamps").due)
        assertEquals("Home", byText.getValue("Read the boiler manual https://example.com/manual.pdf").project)
        // A line of a tag alone keeps it as its words.
        assertEquals("Garden", byText.getValue("+Garden").project)
        // Nothing comes in as a note: todo.txt has no tasks under tasks.
        assertTrue(p.items.all { it.parentKey == null && it.body == null })
    }

    @Test fun `done lines are skipped, and the spec's look-alikes are not`() {
        val texts = read(fixture()).items.map { it.text }
        assertTrue("Mow the lawn" !in texts)
        assertTrue("xylophone lesson" in texts)
        assertTrue("X 2026-01-01 Make resolutions" in texts)
        assertTrue("x Find ticket prices" in texts)
    }

    @Test fun `the summary says what comes in, and what has no place is counted`() {
        val p = read(fixture())
        assertEquals(listOf(
            ImportLine("2 new projects"),
            ImportLine("12 tasks, 3 with a date"),
            ImportLine("1 done — skipped", muted = true),
        ), p.lines)
        assertEquals(listOf(
            "3 tasks' priorities",
            "3 tasks' contexts",
            "1 repeats (the next date is kept)",
            "1 tasks in more than one project — each comes in under its first",
            "2 tasks' creation dates",
        ), p.lost)
    }

    @Test fun `a due date that is not a date stays in the words`() {
        val p = read(file("due:2026-13-45 Check the date\nAsk about it due:soon"))
        assertEquals(listOf("due:2026-13-45 Check the date", "Ask about it due:soon"), p.items.map { it.text })
        assertTrue(p.items.all { it.due == null })
    }

    @Test fun `a project already in the app gets the tasks, whatever its case`() {
        val sink = ImportSourcesTest.Sink()
        ItemWriter(sink).project("garden")
        val p = read(fixture(), sink)
        assertEquals(listOf("Home"), p.newProjects)
        assertEquals("1 new projects, 1 existing ones get new tasks", p.lines.first().text)
        assertEquals("garden", p.items.first { it.text == "Plant tulip bulbs" }.project)
    }

    @Test fun `a second import of the same file adds nothing`() {
        val sink = ImportSourcesTest.Sink()
        val first = read(fixture(), sink)
        assertEquals(ImportWriter.Result(tasks = 12, notes = 0, projects = 2, events = 0), ImportWriter.apply(first, sink))

        val second = read(fixture(), sink)
        assertTrue(second.empty, second.toString())
        assertEquals(ImportLine("12 already in the app — skipped", muted = true), second.lines.last())
        assertEquals(emptyList(), second.lost)
        assertEquals(ImportWriter.Result(0, 0, 0, 0), ImportWriter.apply(second, sink))
    }

    @Test fun `Windows line ends, a byte order mark and blank lines are fine`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val p = read(ImportValue.File("todo.txt", bom + "Plant tulip bulbs +Garden\r\n\r\n  \r\nBuy stamps\r\n".toByteArray()))
        assertEquals(listOf("Plant tulip bulbs", "Buy stamps"), p.items.map { it.text })
        assertEquals(listOf("Garden"), p.newProjects)
    }

    @Test fun `an empty file brings nothing`() {
        assertTrue(read(file("")).empty)
        assertTrue(read(file("\n\nx 2026-10-01 All done\n")).empty)
    }

    @Test fun `a file that isn't text, or is far too big, is refused with a short message`() {
        val binary = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        assertEquals("That isn't a text file.",
            assertFailsWith<IOException> { read(ImportValue.File("photo.png", binary)) }.message)
        assertEquals(TodoTxtSource.MAX_LINES, read(file("Buy stamps\n".repeat(TodoTxtSource.MAX_LINES))).items.size)
        assertEquals("That file is too large.",
            assertFailsWith<IOException> { read(file("Buy stamps\n".repeat(TodoTxtSource.MAX_LINES + 1))) }.message)
        assertEquals("That file is too large.",
            assertFailsWith<IOException> { read(ImportValue.File("todo.txt", ByteArray(ImportValue.FILE_LIMIT + 1) { 'a'.code.toByte() })) }.message)
    }
}
