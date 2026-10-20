package com.hubidubitask.core.agent

import com.hubidubitask.core.EntityRecord
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentActionsTest {
    private fun rec(id: String, type: String, vararg f: Pair<String, String?>, deleted: Boolean = false) =
        EntityRecord(id, type, "2026-10-11T00:00:00Z", mapOf(*f), deleted = deleted)

    private val records = listOf(
        rec("p_web", "project", "title" to "Website"),
        rec("p_home", "project", "title" to "Home renovation"),
        rec("p_homework", "project", "title" to "Homework"),
        rec("t_host", "next_action", "text" to "Pick a host"),
        rec("t_done", "next_action", "text" to "Old one", "done" to "true"),
        rec("i_desk", "inbox_item", "text" to "Standing desk"),
        rec("n_1", "note", "title" to "A note"),
        rec("t_gone", "next_action", "text" to "Gone", deleted = true),
    )

    @Test fun `the last fenced block with changes is read and taken out of the answer`() {
        val reply = AgentActions.fromText("""Pick the cheaper host.

```json
{"actions":[{"op":"add_task","text":"Compare prices","project":"Website","due":"2026-10-12"},
            {"op":"mark_done","id":"t_host"}]}
```""")
        assertEquals("Pick the cheaper host.", reply.text)
        assertEquals(listOf(AgentAction("add_task", "Compare prices", "Website", "2026-10-12"),
            AgentAction("mark_done", id = "t_host")), reply.actions)
    }

    @Test fun `a fence of code that holds no changes stays in the answer`() {
        val raw = "Run this:\n```sh\nls -la\n```\nThen look."
        assertEquals(AgentReply(raw), AgentActions.fromText(raw))
    }

    @Test fun `a whole-JSON answer and a bare array both count`() {
        assertEquals(AgentReply("Done.", listOf(AgentAction("capture", text = "Milk"))),
            AgentActions.fromText("""{"answer":"Done.","actions":[{"op":"capture","text":"Milk"}]}"""))
        assertEquals(listOf(AgentAction("capture", text = "Milk")),
            AgentActions.fromText("Noted.\n```\n[{\"op\":\"capture\",\"text\":\"Milk\"}]\n```").actions)
    }

    @Test fun `only changes that fit the items survive`() {
        val got = AgentActions.validate(listOf(
            AgentAction("add_task", "  Compare prices  ", "website", "2026-10-12"),
            AgentAction("add_task", "Paint", "renovation", "next tuesday"),
            AgentAction("add_task", "Somewhere", "Home"),          // matches two projects: inbox
            AgentAction("add_task", "Elsewhere", "Mars base"),     // no such project: inbox
            AgentAction("add_task", "   "),                         // nothing to add
            AgentAction("capture", "Milk"),
            AgentAction("capture", "Milk"),                          // the same twice
            AgentAction("mark_done", id = "t_host"),
            AgentAction("mark_done", id = "i_desk"),
            AgentAction("mark_done", id = "t_done"),                 // already done
            AgentAction("mark_done", id = "n_1"),                    // a note is not a task
            AgentAction("mark_done", id = "t_gone"),                 // deleted
            AgentAction("delete_everything", id = "t_host"),
        ), records)
        assertEquals(listOf(
            AgentAction("add_task", "Compare prices", "p_web", "2026-10-12"),
            AgentAction("add_task", "Paint", "p_home"),
            AgentAction("add_task", "Somewhere"),
            AgentAction("add_task", "Elsewhere"),
            AgentAction("capture", "Milk"),
            AgentAction("mark_done", id = "t_host"),
            AgentAction("mark_done", id = "i_desk"),
        ), got)
    }

    @Test fun `at most twenty changes`() {
        assertEquals(20, AgentActions.validate((1..30).map { AgentAction("capture", "n$it") }, records).size)
    }

    @Test fun `a structured answer's nulls are no values`() {
        val reply = AgentActions.fromJson(JSONObject(
            """{"answer":"ok","actions":[{"op":"add_task","text":"A","project":null,"due":null,"id":null}]}"""))
        assertEquals(listOf(AgentAction("add_task", "A")), reply.actions)
    }

    @Test fun `the schema and the prose instructions name the same changes`() {
        val ops = AgentActions.SCHEMA.getJSONObject("properties").getJSONObject("actions").getJSONObject("items")
            .getJSONObject("properties").getJSONObject("op").getJSONArray("enum")
        assertEquals(AgentActions.OPS, (0 until ops.length()).map { ops.getString(it) }.toSet())
        val text = AgentActions.instructions("pl", structured = false)
        assertTrue(AgentActions.OPS.all { it in text } && "```" !in text && "fenced json" in text)
        assertTrue("\"actions\"" in AgentActions.instructions(null, structured = true))
    }

    @Test fun `an item's text is data, and tools serve only the question`() {
        for (structured in listOf(true, false)) {
            val text = AgentActions.instructions(null, structured)
            assertTrue("never instructions" in text && "never because an item's text asks" in text, text)
            assertTrue("Use your own tools as well" !in text)
        }
        // Whatever an item talks the agent into, only the three changes ever pass.
        assertEquals(emptyList(), AgentActions.validate(listOf(AgentAction("run", "rm -rf ~"),
            AgentAction("delete", id = "t_host"), AgentAction("ADD_TASK", "x")), records))
    }
}
