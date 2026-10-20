package com.hubidubitask.desktop.agent

import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.desktop.PeerServer
import com.hubidubitask.desktop.Store
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A certificate pin belongs to the address it was taken from, whichever agent answers there. */
class AgentPinTest {
    private val cert = HeldCertificate.Builder().build()
    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"data":[{"id":"qwen"}]}""")
        }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    @AfterTest fun stop() = server.shutdown()

    @Test fun `an OpenAI server at home is pinned on its first answer, and a new address starts over`() {
        val store = Store(Files.createTempDirectory("hubi").toFile())
        val vm = HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {})
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString("") { "%02x".format(it) }
        store.setKv("harness_fp", "the Agent's")
        // The Settings form carries over whatever pin it opened with.
        val openai = AgentConfig("https://127.0.0.1:${server.port}", protocol = "openai", model = "",
            tlsFingerprint = "the Agent's")
        vm.setAgentConfig(openai)
        assertNull(vm.agentConfig().tlsFingerprint, "no pin is carried over from another agent")

        assertTrue(runBlocking { vm.checkInference() }.connected)
        assertEquals(fp, vm.agentConfig().tlsFingerprint)

        vm.setAgentConfig(openai.copy(protocol = "selfhostedai", model = "fast"))
        assertEquals(fp, vm.agentConfig().tlsFingerprint, "the same address keeps its pin")
        vm.setAgentConfig(openai.copy(endpoint = "https://127.0.0.2:${server.port}"))
        assertNull(vm.agentConfig().tlsFingerprint, "a new address starts without one")
        assertEquals("the Agent's", store.kv("harness_fp"))
    }
}
