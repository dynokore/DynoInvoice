package com.dynokore.platform.serviceruntime.securityevents

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A local HTTP server standing in for the OTLP collector: [answers] gives the status of the n-th request (0-based); null never answers. */
class CollectorStub(
    private val answers: (Int) -> Int?,
) : AutoCloseable {
    class Received(
        val method: String,
        val path: String,
        val contentType: String?,
        val body: String,
    )

    val received = CopyOnWriteArrayList<Received>()
    private val gate = CountDownLatch(1)
    private val pool = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val n = received.size
                received +=
                    Received(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        exchange.requestHeaders.getFirst("Content-Type"),
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
