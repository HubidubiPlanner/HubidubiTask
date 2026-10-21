package com.hubidubitask.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTest {

    @Test
    fun `reasoning blocks are dropped, an unfinished one takes the rest`() {
        assertEquals("Answer.", Text.stripReasoning("<think>hmm</think>Answer."))
        assertEquals("Start", Text.stripReasoning("Start<think>and never finished"))
    }

    @Test
    fun `a stored choice the apps no longer offer reads as Claude`() {
        assertEquals("claude", AgentConfig.knownProtocol("retired"))
        assertEquals("claude", AgentConfig.knownProtocol(null))
        assertEquals("openai", AgentConfig.knownProtocol("openai"))
        assertEquals("harness", AgentConfig.knownProtocol("harness"))
    }
}
