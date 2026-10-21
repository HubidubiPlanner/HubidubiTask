package com.hubidubitask.core.agent

import com.hubidubitask.core.i18n.t
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import javax.net.ssl.SSLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One TLS policy for every agent: standard TLS for a public host; at home, an
 * authority, a pin or the first certificate seen. Loopback is in the home
 * network, so "public" is played by telling the client so.
 */
class AgentHttpTest {
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    @AfterTest fun stop() = server.shutdown()

    private val base get() = "https://127.0.0.1:${server.port}"
    private val fp = Agent.fingerprint(cert.certificate)

    private fun get(pin: String?, public: Boolean, seen: (String) -> Unit = {}): Int {
        server.enqueue(MockResponse().setBody("{}"))
        val http = Agent.http(base, pin, 5, 5, seen = seen, isPrivate = { !public })
        return http.newCall(Request.Builder().url("$base/v1/models").build()).execute().use { it.code }
    }

    @Test fun `at home, a self-signed certificate is trusted on first use, and reported for pinning`() {
        val seen = mutableListOf<String>()
        assertEquals(200, get(null, public = false) { seen += it })
        assertEquals(listOf(fp), seen)
    }

    @Test fun `at home, a pin admits its certificate and no other`() {
        assertEquals(200, get(fp, public = false))
        assertEquals(200, get("sha256:" + fp.chunked(2).joinToString(":").uppercase(), public = false))
        assertFailsWith<SSLException> { get("00".repeat(32), public = false) }
        assertEquals(2, server.requestCount, "nothing reaches the server past a wrong pin")
    }

    @Test fun `a public host has the system's authorities only, pinned or not`() {
        val seen = mutableListOf<String>()
        assertFailsWith<SSLException> { get(null, public = true) { seen += it } }
        assertFailsWith<SSLException> { get(fp, public = true) }
        assertEquals(0, server.requestCount)
        assertTrue(seen.isEmpty(), "nothing to pin")
    }

    @Test fun `plain HTTP goes only to the home network`() {
        val stub = Stub { 200 to "{}" }
        try {
            fun call(private: Boolean) = Agent.http(stub.base, null, 5, isPrivate = { private })
                .newCall(Request.Builder().url(stub.base + "/v1/models").build()).execute().use { it.code }
            val e = assertFailsWith<AgentError> { call(private = false) }
            assertEquals(t("Plain HTTP is only for your home network. Use an https:// address."), e.message)
            assertTrue(stub.calls.isEmpty(), "no request was sent")
            assertEquals(200, call(private = true))
        } finally { stub.close() }
    }

    @Test fun `every client keeps to it`() {
        val error = t("Plain HTTP is only for your home network. Use an https:// address.")
        val public = "http://8.8.8.8:9"
        assertEquals(error, OpenAiClient(AgentConfig(public, protocol = "openai")).health().error)
        assertEquals(error, OpencodeAgent(AgentConfig(public, protocol = "harness", kind = "opencode")).health().error)
        assertEquals(error, Agent.clientFor(AgentConfig(public, protocol = "harness", kind = "hermes")).health().error)
    }
}
