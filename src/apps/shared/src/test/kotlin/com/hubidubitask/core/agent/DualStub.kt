package com.hubidubitask.core.agent

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * One port on 127.0.0.1 that answers TLS and plain HTTP both, as a confused
 * server or someone on the path could; each connection's first byte says
 * which. Over TLS it shows [certs] in turn (the last one from then on), and
 * with [tls] null it finishes the handshake and hangs up. Remembers every
 * request, and how it came.
 */
class DualStub(
    private val certs: List<HeldCertificate>,
    private val tls: ((Stub.Call) -> Pair<Int, String>?)?,
    private val plain: (Stub.Call) -> Pair<Int, String>?,
) : AutoCloseable {
    data class Hit(val tls: Boolean, val call: Stub.Call)

    val hits = CopyOnWriteArrayList<Hit>()
    private var handshakes = 0
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { s.use(::serve) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        val first = socket.getInputStream().read()
        if (first < 0) return
        val consumed = ByteArrayInputStream(byteArrayOf(first.toByte()))
        if (first != 0x16) return answer(SequenceInputStream(consumed, socket.getInputStream()),
            socket.getOutputStream(), false, plain)
        val cert = synchronized(this) { certs[minOf(handshakes++, certs.size - 1)] }
        val factory = HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory()
        (factory.createSocket(socket, consumed, true) as SSLSocket).use { ssl ->
            ssl.useClientMode = false
            ssl.startHandshake()
            answer(ssl.inputStream, ssl.outputStream, true, tls ?: return)
        }
    }

    /** One HTTP/1.1 request read and answered; the connection closes after it. */
    private fun answer(input: InputStream, output: OutputStream, overTls: Boolean,
                       routes: (Stub.Call) -> Pair<Int, String>?) {
        val head = generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()
        val words = head.firstOrNull()?.split(" ")?.takeIf { it.size >= 2 } ?: return
        val auth = head.drop(1).firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val call = Stub.Call(words[0], words[1].substringBefore('?'), auth, "")
        hits += Hit(overTls, call)
        val (code, body) = routes(call) ?: (404 to """{"error":{"message":"not here"}}""")
        val bytes = body.toByteArray()
        output.write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
        output.flush()
    }

    private fun line(input: InputStream): String? {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return b.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            b.append(c.toChar())
        }
    }

    override fun close() = server.close()
}
