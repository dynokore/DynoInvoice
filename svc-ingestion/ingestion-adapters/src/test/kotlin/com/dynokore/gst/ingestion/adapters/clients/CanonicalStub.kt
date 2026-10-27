package com.dynokore.gst.ingestion.adapters.clients

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** What the stub of canonical-svc saw for one request. */
data class SeenRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

/** A stub HTTP server of canonical-svc: it records every request and answers as [reply] says. */
class CanonicalStub : AutoCloseable {
    class Reply(
        val status: Int,
        val body: String = "",
        val delayMillis: Long = 0,
    )

    val seen = CopyOnWriteArrayList<SeenRequest>()

    @Volatile
    var reply: Reply = Reply(500)

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = this@CanonicalStub.executor
            start()
        }

    val url: String = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        seen +=
            SeenRequest(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                body,
            )
        val answer = reply
        if (answer.delayMillis > 0) Thread.sleep(answer.delayMillis)
        val bytes = answer.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        runCatching {
            exchange.sendResponseHeaders(answer.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
        }
        exchange.close()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
