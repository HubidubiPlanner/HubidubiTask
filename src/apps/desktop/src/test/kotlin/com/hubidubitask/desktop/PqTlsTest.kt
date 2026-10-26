package com.hubidubitask.desktop

import com.hubidubitask.core.peer.PeerPairing
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * The computer's TLS really agrees the post-quantum key exchange
 * (X25519MLKEM768) on the JDK the app bundles. JDK 17 can't, so this runs
 * only as `./gradlew :desktop:pqTlsTest`, on that JDK, and packaging runs it
 * first. One side of each handshake offers or takes nothing but that one
 * group, so a handshake that completes can only have used it. The phone's
 * side (Conscrypt) is checked by hand with the debug TlsCheck.
 */
class PqTlsTest {
    private val dir = Files.createTempDirectory("pq").toFile()
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun stop() {
        closers.forEach { it() }
        dir.deleteRecursively()
    }

    @Test
    fun `the sync server agrees the post-quantum key exchange`() {
        val server = PeerServer(Store(dir), "Test Mac", MemoryPeerKeys(), listenPort = 0,
            admit = { true }, announce = false) {}.also { it.start(); closers += it::stop }
        val pinned = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = throw CertificateException()
            override fun checkServerTrusted(c: Array<out X509Certificate>, a: String?) {
                if (PeerPairing.sha256Hex(c[0].encoded) != server.fingerprint) throw CertificateException("not the server's")
            }
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinned), null) }
        (tls.socketFactory.createSocket("127.0.0.1", server.port) as SSLSocket).use { s ->
            s.sslParameters = s.sslParameters.only(PQ)
            s.startHandshake()
            assertEquals("TLSv1.3", s.session.protocol)
        }
    }

    @Test
    fun `the app's HTTPS client offers the post-quantum key exchange`() {
        val web = pqOnlyServer()
        web.enqueue(MockResponse().setBody("ok"))
        // The trust is the test's; the key exchange is the default every client of the app gets.
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        client.newCall(get(web)).execute().use {
            assertEquals(200, it.code)
            assertEquals("TLSv1.3", it.handshake?.tlsVersion?.javaName)
        }
    }

    @Test
    fun `a client without it is refused, so the checks above can fail`() {
        val web = pqOnlyServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .sslSocketFactory(Limited(trust.sslSocketFactory(), "x25519"), trust.trustManager).build()
        assertFailsWith<SSLException> { client.newCall(get(web)).execute() }
    }

    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()

    /** An HTTPS server that takes no key exchange but the post-quantum one. */
    private fun pqOnlyServer() = MockWebServer().apply {
        useHttps(Limited(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), PQ), false)
        start(InetAddress.getByName("127.0.0.1"), 0)
        closers += { shutdown() }
    }

    // By address: web.url() says "localhost", which tries ::1 first, where nothing listens.
    private fun get(web: MockWebServer) = Request.Builder().url("https://127.0.0.1:${web.port}/").build()

    /** [d]'s sockets, each limited to the key exchange [group]. */
    private class Limited(private val d: SSLSocketFactory, private val group: String) : SSLSocketFactory() {
        private fun Socket.limit(): Socket {
            val s = this as SSLSocket
            s.sslParameters = s.sslParameters.only(group)
            return s
        }

        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        override fun createSocket(): Socket = d.createSocket().limit()
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            d.createSocket(s, host, port, autoClose).limit()
        override fun createSocket(host: String, port: Int): Socket = d.createSocket(host, port).limit()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            d.createSocket(host, port, localHost, localPort).limit()
        override fun createSocket(host: InetAddress, port: Int): Socket = d.createSocket(host, port).limit()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            d.createSocket(address, port, localAddress, localPort).limit()
    }

    private companion object {
        const val PQ = "X25519MLKEM768"

        /** Only [group] for the key exchange. SSLParameters.setNamedGroups is JDK 20+; the tests compile for 17. */
        fun SSLParameters.only(group: String): SSLParameters = apply {
            val set = runCatching { SSLParameters::class.java.getMethod("setNamedGroups", Array<String>::class.java) }
                .getOrElse { fail("needs the JDK the app bundles (27 or newer): ./gradlew :desktop:pqTlsTest") }
            set.invoke(this, arrayOf(group))
        }
    }
}
