package com.hubidubitask.core.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** A plain-HTTP server on 127.0.0.1 that answers by path, and remembers what it was asked. */
class Stub(private val routes: (Stub.Call) -> Pair<Int, String>?) : AutoCloseable {
    data class Call(val method: String, val path: String, val auth: String?, val body: String)

    val calls = mutableListOf<Call>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val call = Call(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"),
                ex.requestBody.readBytes().decodeToString())
            synchronized(calls) { calls += call }
            val (code, body) = routes(call) ?: (404 to """{"error":{"message":"not here"}}""")
            val out = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, if (out.isEmpty()) -1 else out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    val port: Int get() = server.address.port
    val base: String get() = "http://127.0.0.1:$port"

    override fun close() = server.stop(0)
}
