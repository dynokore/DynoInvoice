package com.dynokore.platform.serviceruntime.tracing

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The OTLP collector stand-in for binary bodies (the securityevents CollectorStub keeps the body as text, which would
 * corrupt protobuf). [answers] gives the status of the n-th request (0-based); null never answers.
 */
class TraceCollectorStub(
    private val answers: (Int) -> Int?,
) : AutoCloseable {
    class Received(
        val method: String,
        val path: String,
        val contentType: String?,
        val contentEncoding: String?,
        val body: ByteArray,
    )

    val received = CopyOnWriteArrayList<Received>()
    private val gate = CountDownLatch(1)
    private val pool = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                val body = exchange.requestBody.readAllBytes()
                val n = received.size
                received +=
                    Received(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        exchange.requestHeaders.getFirst("Content-Type"),
                        exchange.requestHeaders.getFirst("Content-Encoding"),
                        body,
                    )
                val status = answers(n)
                if (status == null) {
                    gate.await(30, TimeUnit.SECONDS)
                } else {
                    exchange.sendResponseHeaders(status, -1)
                }
                exchange.close()
            }
            start()
        }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        gate.countDown()
        server.stop(0)
        pool.shutdownNow()
    }
}
