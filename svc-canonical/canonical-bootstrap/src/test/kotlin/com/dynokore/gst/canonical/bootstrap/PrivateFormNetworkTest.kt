package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * INV-REQ-1081, INV-AC-19914: answering a dry-run sample request in a private deployment opens no connection outside
 * the event log and the database. The network of the test profile is restricted by a JVM-wide [ProxySelector]: every
 * `java.net.Socket` connect and every URL connection asks it first (the JDK consults the default selector with
 * `socket://host:port` for a plain socket), and it records each target. The allowed set is the database host and port
 * (the event log of the test profile is the in-memory reader of the harness, so it opens no connection).
 */
class PrivateFormNetworkTest {
    /** Records every connection target asked of it; the allowed set is compared by exact host and port. */
    private class Guard(
        private val allowed: Set<Pair<String, Int>>,
    ) : ProxySelector() {
        val attempts = CopyOnWriteArrayList<Pair<String, Int>>()

        override fun select(uri: URI?): List<Proxy> {
            if (uri != null) attempts += uri.host.orEmpty() to uri.port
            return listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(
            uri: URI?,
            sa: SocketAddress?,
            ioe: IOException?,
        ) = Unit

        fun outside(): List<Pair<String, Int>> = attempts.filter { it !in allowed }
    }

    private fun databaseTarget(): Pair<String, Int> {
        val uri = URI(CanonicalDb.jdbcUrl.removePrefix("jdbc:"))
        return uri.host to uri.port
    }

    private fun <T> restricted(
        guard: Guard,
        body: () -> T,
    ): T {
        val before = ProxySelector.getDefault()
        ProxySelector.setDefault(guard)
        try {
            return body()
        } finally {
            ProxySelector.setDefault(before)
        }
    }

    private fun seed(h: SampleExchangeKit.Harness) {
        h.seedEntity()
        h.seedDocuments((1..3).map { h.entry(it) } + h.entry(4, code = 381))
    }

    private fun pagesOf(rows: List<Pair<String, JsonNode>>): List<JsonNode> =
        rows.map { (_, page) ->
            (page as tools.jackson.databind.node.ObjectNode).deepCopy().also { it.remove("dryRunId") }
        }

    @Test
    fun `INV-AC-19914 the restricted run writes the pages of the unrestricted run and opens no other connection`(
        @TempDir dir: Path,
    ) {
        SampleExchangeKit.context(dir).run { context ->
            val h = SampleExchangeKit.Harness(context)
            seed(h)
            h.send("dry-free")
            h.poll().applied shouldBe 1
            val guard = Guard(setOf(databaseTarget()))
            h.send("dry-restricted")
            restricted(guard) { h.poll().applied shouldBe 1 }
            val free = h.pages("dry-free")
            val limited = h.pages("dry-restricted")
            limited.size shouldBe free.size
            pagesOf(limited) shouldBe pagesOf(free)
            limited.map { it.second.path("pageNumber").asInt() } shouldBe
                free.map { it.second.path("pageNumber").asInt() }
            limited.map { it.second.path("lastPage").asBoolean() } shouldBe
                free.map { it.second.path("lastPage").asBoolean() }
            guard.outside() shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-19914 the guard records a call to a URL outside the allowed set`() {
        val guard = Guard(setOf(databaseTarget()))
        restricted(guard) {
            runCatching { Socket().use { it.connect(InetSocketAddress("192.0.2.1", 80), 1) } }
        }
        guard.outside() shouldBe listOf("192.0.2.1" to 80)
    }

    @Test
    fun `INV-AC-19914 the database host on another port is outside the allowed set`() {
        val (host, port) = databaseTarget()
        val guard = Guard(setOf(host to port))
        restricted(guard) {
            runCatching { Socket().use { it.connect(InetSocketAddress(host, port + 1), 1) } }
        }
        guard.outside() shouldBe listOf(host to port + 1)
    }
}
