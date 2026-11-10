package com.dynokore.gst.rules.bootstrap

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One request the stub of access-svc saw: the path and the query as a multimap, in the order received. */
data class AccessRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
)

/**
 * A stub HTTP server of access-svc for the three operations of access/openapi.yaml that rules-svc calls
 * (`decidePackApproval`, `getInstallationApproverFact`, `getMappingAcknowledgement`): it records every request and
 * answers each path as its [replies] entry says; a path with no reply answers 500.
 */
class AccessStubServer : AutoCloseable {
    class Reply(
        val status: Int,
        val body: String = "",
        val delayMillis: Long = 0,
    )

    val seen = CopyOnWriteArrayList<AccessRequest>()
    val replies = ConcurrentHashMap<String, Reply>()

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = this@AccessStubServer.executor
            start()
        }

    val url: String = "http://127.0.0.1:${server.address.port}"

    fun reset() {
        seen.clear()
        replies.clear()
    }

    fun requestsTo(path: String): List<AccessRequest> = seen.filter { it.path == path }

    private fun handle(exchange: HttpExchange) {
        exchange.requestBody.readAllBytes()
        val path = exchange.requestURI.path
        seen += AccessRequest(exchange.requestMethod, path, queryOf(exchange.requestURI.rawQuery))
        val answer = replies[path] ?: Reply(INTERNAL_ERROR)
        if (answer.delayMillis > 0) Thread.sleep(answer.delayMillis)
        val bytes = answer.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        runCatching {
            exchange.sendResponseHeaders(answer.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
        }
        exchange.close()
    }

    private fun queryOf(raw: String?): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.forEach {
            val key = URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8)
            out.getOrPut(key) { mutableListOf() } += URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        return out
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        const val DECISION = "/internal/v1/access/pack-approval-decision"
        const val FACT = "/internal/v1/access/installation/approver-fact"
        const val ACKNOWLEDGEMENT = "/internal/v1/access/mapping-acknowledgement"
        private const val INTERNAL_ERROR = 500
    }
}
