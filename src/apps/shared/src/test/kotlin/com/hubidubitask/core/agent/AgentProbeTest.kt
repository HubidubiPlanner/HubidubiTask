package com.hubidubitask.core.agent

import com.hubidubitask.core.agent.AgentProbe.Result
import okhttp3.tls.HeldCertificate
import java.net.ServerSocket
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Which agent listens at an address, found from the address alone. */
class AgentProbeTest {
    private val stubs = mutableListOf<AutoCloseable>()
    @AfterTest fun stop() = stubs.forEach { it.close() }
    private fun stub(routes: (Stub.Call) -> Pair<Int, String>?) = Stub(routes).also { stubs += it }
    private fun dual(certs: List<HeldCertificate>, tls: ((Stub.Call) -> Pair<Int, String>?)?,
                     plain: (Stub.Call) -> Pair<Int, String>?) = DualStub(certs, tls, plain).also { stubs += it }

    private fun bearer(token: String, c: Stub.Call) = c.auth == "Bearer $token"
    private fun basic(user: String, pw: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$pw".toByteArray())

    private val openclaw = { token: String? -> stub { c -> when (c.path) {
        "/health" -> 200 to """{"ok":true,"status":"live"}"""
        "/v1/models" -> if (token == null || bearer(token, c)) 200 to """{"data":[{"id":"openclaw"}]}""" else 401 to "{}"
        else -> null } } }

    private fun detect(s: Stub, token: String?) = AgentProbe.detect("127.0.0.1", s.port, token)

    /** OpenClaw's answers, its models only for [token]. */
    private fun openclawRoutes(token: String) = { c: Stub.Call -> when (c.path) {
        "/health" -> 200 to """{"ok":true,"status":"live"}"""
        "/v1/models" -> if (bearer(token, c)) 200 to """{"data":[{"id":"openclaw"}]}""" else 401 to "{}"
        else -> null } }
    private val certA = HeldCertificate.Builder().build()
    private val certB = HeldCertificate.Builder().build()

    @Test fun `OpenClaw is found by its health, and its token checked`() {
        val s = openclaw("tok")
        val found = assertIs<Result.Found>(detect(s, "tok"))
        assertEquals(AgentKind.OPENCLAW, found.kind)
        assertEquals("openclaw", found.model)
        assertEquals("http://127.0.0.1:${s.port}", found.endpoint, "plain HTTP inside the home network")
        assertEquals("OpenClaw", found.label)
        assertEquals(Result.NeedsToken(AgentKind.OPENCLAW, s.base, refused = false), detect(s, null))
        assertEquals(Result.NeedsToken(AgentKind.OPENCLAW, s.base, refused = true), detect(s, "wrong"))
    }

    @Test fun `Hermes is told apart from OpenClaw`() {
        val s = stub { c -> when (c.path) {
            "/health" -> 200 to """{"status":"ok"}"""
            "/v1/models" -> if (bearer("key", c)) 200 to """{"data":[{"id":"hermes-agent"}]}""" else 401 to "{}"
            else -> null } }
        val found = assertIs<Result.Found>(detect(s, "key"))
        assertEquals(AgentKind.HERMES, found.kind); assertEquals("hermes-agent", found.model)
    }

    @Test fun `opencode and Kilo answer on their own path, with a password`() {
        val opencode = stub { c -> when {
            c.path == "/global/health" && c.auth == basic("opencode", "pw") -> 200 to """{"healthy":true,"version":"1.19.0"}"""
            c.path == "/global/health" -> 401 to "{}"
            else -> null } }
        assertEquals(AgentKind.OPENCODE, assertIs<Result.Found>(detect(opencode, "pw")).kind)
        assertEquals(Result.NeedsToken(AgentKind.OPENCODE, opencode.base, refused = false), detect(opencode, null))
        assertEquals(Result.NeedsToken(AgentKind.OPENCODE, opencode.base, refused = true), detect(opencode, "nope"))

        val kilo = stub { c -> when {
            c.path == "/global/health" && c.auth == basic("kilo", "pw") -> 200 to """{"healthy":true}"""
            c.path == "/global/health" -> 401 to "{}"
            c.path == "/indexing/status" && c.auth == basic("kilo", "pw") -> 200 to "{}"
            else -> null } }
        assertEquals(AgentKind.KILO, assertIs<Result.Found>(detect(kilo, "pw")).kind)

        val open = stub { c -> if (c.path == "/global/health") 200 to """{"healthy":true}""" else null }
        assertEquals(AgentKind.OPENCODE, assertIs<Result.Found>(detect(open, null)).kind)
    }

    @Test fun `any other OpenAI-shaped server is found by its models`() {
        val s = stub { c -> if (c.path == "/v1/models") 200 to """{"data":[{"id":"qwen3.6-35b"}]}""" else null }
        val found = assertIs<Result.Found>(detect(s, null))
        assertEquals(AgentKind.OPENAI, found.kind); assertEquals("qwen3.6-35b", found.model)
        assertEquals("qwen3.6-35b", found.label)
    }

    /** Ollama as it answers: its models at /api/tags and /v1/models, no token, nothing at /health. */
    private fun ollama() = stub { c -> when (c.path) {
        "/api/tags" -> 200 to """{"models":[{"name":"llama3.2:latest","model":"llama3.2:latest","size":2019393189}]}"""
        "/v1/models" -> 200 to """{"object":"list","data":[{"id":"llama3.2:latest","object":"model","owned_by":"library"}]}"""
        else -> 404 to "404 page not found" } }

    @Test fun `Ollama is found by its model list, and needs no token`() {
        val s = ollama()
        val found = assertIs<Result.Found>(detect(s, null))
        assertEquals(AgentKind.OLLAMA, found.kind)
        assertEquals("Ollama", found.label)
        assertEquals("llama3.2:latest", found.model)
        assertEquals("http://127.0.0.1:${s.port}", found.endpoint)
        // A token typed anyway is never sent: Ollama asks for none.
        assertEquals(AgentKind.OLLAMA, assertIs<Result.Found>(detect(s, "tok")).kind)
        assertTrue(s.calls.all { it.auth == null }, "${s.calls}")
        // Its port is tried when none is typed, and the catch-all stays last.
        assertTrue(11434 in AgentKind.DEFAULT_PORTS)
        assertEquals(AgentKind.OPENAI, AgentKind.entries.last())
    }

    @Test fun `a plain OpenAI-shaped server is not taken for Ollama`() {
        val models = """{"data":[{"id":"qwen3.6-35b"}]}"""
        // A bare 200 at /api/tags, or one without Ollama's "models", is not Ollama.
        for (tags in listOf("{}", """{"data":[]}""", "ok")) {
            val s = stub { c -> when (c.path) { "/v1/models" -> 200 to models; "/api/tags" -> 200 to tags; else -> null } }
            assertEquals(AgentKind.OPENAI, assertIs<Result.Found>(detect(s, null)).kind, tags)
        }
    }

    @Test fun `Ollama is not taken for OpenClaw or Hermes, nor they for it`() {
        assertEquals(AgentKind.OLLAMA, assertIs<Result.Found>(detect(ollama(), null)).kind)
        val openclaw = openclaw(null)
        assertEquals(AgentKind.OPENCLAW, assertIs<Result.Found>(detect(openclaw, null)).kind)
        val hermes = stub { c -> when (c.path) {
            "/health" -> 200 to """{"status":"ok"}"""
            "/v1/models" -> 200 to """{"data":[{"id":"hermes-agent"}]}"""
            else -> null } }
        assertEquals(AgentKind.HERMES, assertIs<Result.Found>(detect(hermes, null)).kind)
        assertTrue((openclaw.calls + hermes.calls).none { it.path == "/api/tags" }, "found by /health first")
    }

    @Test fun `nothing there, or something else, is not an agent`() {
        val web = stub { null }
        assertEquals(Result.NotFound, detect(web, null))
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(Result.NotFound, AgentProbe.detect("127.0.0.1", closed, null))
    }

    @Test fun `without a port, each kind's usual one is tried`() {
        val s = openclaw(null)
        val closed = ServerSocket(0).use { it.localPort }
        val found = assertIs<Result.Found>(AgentProbe.detect("127.0.0.1", null, null, ports = listOf(closed, s.port)))
        assertEquals(s.port, AgentLink.split(found.endpoint).second.toInt())
    }

    @Test fun `the token waits until the agent is found without it`() {
        val s = openclaw("tok")
        assertIs<Result.Found>(detect(s, "tok"))
        assertEquals(listOf("/global/health", "/health", "/v1/models"), s.calls.takeWhile { it.auth == null }.map { it.path })
        assertEquals(listOf("/v1/models"), s.calls.filter { it.auth != null }.map { it.path })
    }

    @Test fun `over TLS the token waits too, and the port is never asked in the clear`() {
        val s = dual(listOf(certA), tls = openclawRoutes("tok"), plain = openclawRoutes("tok"))
        val found = assertIs<Result.Found>(AgentProbe.detect("127.0.0.1", s.port, "tok"))
        assertEquals("https://127.0.0.1:${s.port}", found.endpoint)
        assertEquals(Agent.fingerprint(certA.certificate), found.fingerprint, "pinned from now on")
        assertTrue(s.hits.all { it.tls }, "${s.hits}")
        assertEquals(listOf("/global/health", "/health", "/v1/models"), s.hits.takeWhile { it.call.auth == null }.map { it.call.path })
        assertEquals(listOf("/v1/models"), s.hits.filter { it.call.auth != null }.map { it.call.path })
    }

    @Test fun `a TLS server that hangs up is not asked again over plain HTTP`() {
        val s = dual(listOf(certA), tls = null, plain = openclawRoutes("tok"))
        assertEquals(Result.NotFound, AgentProbe.detect("127.0.0.1", s.port, "tok"))
        assertTrue(s.hits.isEmpty(), "${s.hits}")
    }

    @Test fun `only the certificate first seen is trusted for the rest of the probe`() {
        val s = dual(listOf(certA, certB), tls = openclawRoutes("tok"), plain = openclawRoutes("tok"))
        assertEquals(Result.NotFound, AgentProbe.detect("127.0.0.1", s.port, "tok"))
        assertEquals(listOf(DualStub.Hit(true, Stub.Call("GET", "/global/health", null, ""))), s.hits.toList(),
            "nothing after another certificate showed up, the token least of all")
    }

    @Test fun `plain HTTP only inside the home network`() {
        val s = openclaw(null)
        assertEquals(Result.NotFound, AgentProbe.detect("127.0.0.1", s.port, null, plainAllowed = false))
        assertTrue(AgentLink.isPrivate("192.168.1.62")); assertTrue(AgentLink.isPrivate("10.0.0.2"))
        assertTrue(AgentLink.isPrivate("100.101.102.103")); assertTrue(AgentLink.isPrivate("fd00::5"))
        assertTrue(AgentLink.isPrivate("127.0.0.1"))
        assertFalse(AgentLink.isPrivate("8.8.8.8")); assertFalse(AgentLink.isPrivate("100.128.0.1"))
    }

    @Test fun `an IP and a port make the address, and come back out of it`() {
        assertEquals("192.168.1.50" to 18789, AgentLink.parse(" 192.168.1.50 ", "18789"))
        assertEquals("192.168.1.50" to 9000, AgentLink.parse("192.168.1.50:9000", ""))
        assertEquals("agent.local" to null, AgentLink.parse("https://agent.local/", ""))
        assertEquals("fd00::5" to 4096, AgentLink.parse("[fd00::5]:4096", ""))
        assertEquals("fd00::5" to null, AgentLink.parse("fd00::5", "x"))
        assertEquals(null, AgentLink.parse("  ", "8765"))
        assertEquals("http://[fd00::5]:4096", AgentLink.endpoint("http", "fd00::5", 4096))
        assertEquals("https://192.168.1.50", AgentLink.endpoint("https", "192.168.1.50", null))
        assertEquals("192.168.1.50" to "9000", AgentLink.split("http://192.168.1.50:9000"))
        assertEquals("fd00::5" to "4096", AgentLink.split("https://[fd00::5]:4096"))
        assertEquals("192.168.1.50" to "", AgentLink.split("https://192.168.1.50"))
        assertEquals("" to "", AgentLink.split(null))
    }
}
