package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.clients.access.api.SeparationOfDutyApi
import com.dynokore.gst.payload.adapters.clients.masterdata.api.RegisterAndRatesApi
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The build generates the masterdata client beside the access client, each in its own package (INV-T-24313): the test
 * source uses both generated classes by their package names, at compile time only.
 */
class ContractClientsGeneratedTest {
    private data class Seen(
        val method: String,
        val path: String,
        val query: List<Pair<String, String>>,
    )

    private fun stub(
        seen: MutableList<Seen>,
        body: ByteArray,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val raw = exchange.requestURI.rawQuery.orEmpty()
            val query =
                raw.split('&').filter { it.isNotEmpty() }.map {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                }
            seen += Seen(exchange.requestMethod, exchange.requestURI.path, query)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        return server
    }

    @Test
    fun `INV-AC-24311 the generated masterdata client calls getRegisterAndRates with the four query parameters`() {
        val seen = CopyOnWriteArrayList<Seen>()
        val server = stub(seen, """{"register":{"status":"REGISTERED","asOf":"2026-03-19"}}""".toByteArray())
        try {
            val api = RegisterAndRatesApi("http://127.0.0.1:${server.address.port}")
            val result =
                api.getRegisterAndRates(
                    counterpartyUen = "200012345K",
                    currency = "SGD",
                    transactionDate = LocalDate.of(2026, 3, 19),
                    entityRef = "ENT-1",
                )
            seen.size shouldBe 1
            val request = seen.single()
            request.method shouldBe "GET"
            request.path shouldBe "/internal/v1/masterdata/register-and-rates"
            request.query.toMap() shouldBe
                mapOf(
                    "counterpartyUen" to "200012345K",
                    "currency" to "SGD",
                    "transactionDate" to "2026-03-19",
                    "entityRef" to "ENT-1",
                )
            request.query.size shouldBe 4
            result.register.status.value shouldBe "REGISTERED"
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `INV-AC-24311 the generated access client is available by its own package`() {
        val api: SeparationOfDutyApi = SeparationOfDutyApi("http://127.0.0.1:1")
        api.toString().isNotEmpty() shouldBe true
    }
}
