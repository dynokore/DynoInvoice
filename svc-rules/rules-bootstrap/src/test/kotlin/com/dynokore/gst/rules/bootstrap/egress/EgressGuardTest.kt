package com.dynokore.gst.rules.bootstrap.egress

import com.dynokore.gst.rules.bootstrap.PostgresRules
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.time.Duration

private const val UNROUTABLE = "192.0.2.1"
private const val DISCARD_PORT = 9
private const val CONNECT_MILLIS = 300
private const val DB_PORT = 5432

/**
 * The probes of the `EgressGuard` fixture (INV-REQ-1081). 192.0.2.1 is TEST-NET-1 (RFC 5737): never routed, so every
 * attempt below fails or times out and nothing leaves the machine; the guard records the attempt before it proceeds.
 */
class EgressGuardTest {
    private val unroutable = Destination(UNROUTABLE, DISCARD_PORT)

    private fun attempt(connect: () -> Unit) {
        try {
            connect()
        } catch (_: IOException) {
            // the attempt may fail or time out; the guard records it before the connect proceeds
        }
    }

    private fun socket(
        host: String,
        port: Int,
    ) = attempt { Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(host), port), CONNECT_MILLIS) } }

    private fun blockingChannel(host: String) =
        attempt {
            SocketChannel.open().use { it.socket().connect(InetSocketAddress(host, DISCARD_PORT), CONNECT_MILLIS) }
            SocketChannel.open().use { it.connect(InetSocketAddress(host, DISCARD_PORT)) }
        }

    private fun nonBlockingChannel(host: String) =
        attempt {
            Selector.open().use { selector ->
                SocketChannel.open().use { channel ->
                    channel.configureBlocking(false)
                    channel.register(selector, SelectionKey.OP_CONNECT)
                    channel.connect(InetSocketAddress(host, DISCARD_PORT))
                }
            }
        }

    private fun httpClient(host: String) =
        attempt {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_MILLIS.toLong())).build()
            val request =
                HttpRequest
                    .newBuilder(URI("http://$host:$DISCARD_PORT/"))
                    .timeout(Duration.ofMillis(CONNECT_MILLIS.toLong()))
                    .build()
            client.send(request, HttpResponse.BodyHandlers.discarding())
        }

    @Test
    fun `INV-AC-22534 a Socket connect to 192_0_2_1 port 9 is recorded and is a violation`() {
        EgressGuard.install().use { guard ->
            socket(UNROUTABLE, DISCARD_PORT)
            guard.recorded() shouldContain unroutable
            guard.violations() shouldContain unroutable
            assertThrows(AssertionError::class.java) { guard.assertNoViolations() }
        }
    }

    @Test
    fun `INV-AC-22534 a blocking SocketChannel connect to 192_0_2_1 port 9 is recorded`() {
        EgressGuard.install().use { guard ->
            blockingChannel(UNROUTABLE)
            guard.recorded() shouldContain unroutable
            guard.violations() shouldContain unroutable
        }
    }

    @Test
    fun `INV-AC-22534 a non-blocking SocketChannel registered with a Selector connecting to 192_0_2_1 port 9 is recorded`() {
        EgressGuard.install().use { guard ->
            nonBlockingChannel(UNROUTABLE)
            guard.recorded() shouldContain unroutable
        }
    }

    @Test
    fun `INV-AC-22534 an HttpClient request to 192_0_2_1 port 9 is recorded`() {
        EgressGuard.install().use { guard ->
            httpClient(UNROUTABLE)
            guard.recorded() shouldContain unroutable
        }
    }

    @Test
    fun `INV-AC-22534 an HttpURLConnection to 192_0_2_1 port 9 is recorded`() {
        EgressGuard.install().use { guard ->
            attempt {
                val connection = URI("http://$UNROUTABLE:$DISCARD_PORT/").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_MILLIS
                connection.responseCode
            }
            guard.recorded() shouldContain unroutable
        }
    }

    @Test
    fun `INV-AC-22534 127_0_0_2 a look-alike of the loopback entry is recorded and is a violation`() {
        EgressGuard.install().use { guard ->
            socket("127.0.0.2", DISCARD_PORT)
            guard.recorded() shouldContain Destination("127.0.0.2", DISCARD_PORT)
            guard.violations() shouldContain Destination("127.0.0.2", DISCARD_PORT)
        }
    }

    @Test
    fun `INV-AC-22534 the allowlisted database connection is not flagged`() {
        val db = InetAddress.getByName(PostgresRules.pg.host).hostAddress
        val port = PostgresRules.pg.getMappedPort(DB_PORT)
        EgressGuard.install(EgressGuard.Allowed(db, port)).use { guard ->
            socket(db, port)
            guard.recorded() shouldContain Destination(db, port)
            guard.violations() shouldNotContain Destination(db, port)
            guard.assertNoViolations()
        }
    }

    @Test
    fun `INV-AC-22534 the allowlisted database host on another port is recorded and is a violation`() {
        val db = InetAddress.getByName(PostgresRules.pg.host).hostAddress
        val port = PostgresRules.pg.getMappedPort(DB_PORT)
        val other = Destination(db, port + 1)
        EgressGuard.install(EgressGuard.Allowed(db, port)).use { guard ->
            socket(db, port + 1)
            guard.recorded() shouldContain other
            if (db != EgressGuard.LOOPBACK.host) guard.violations() shouldContain other
        }
    }

    @Test
    fun `INV-AC-22534 a host entry with a port admits only that port, a host entry of another address admits nothing else`() {
        val entry = EgressGuard.Allowed(UNROUTABLE, DB_PORT)
        entry.admits(Destination(UNROUTABLE, DB_PORT)) shouldBe true
        entry.admits(Destination(UNROUTABLE, DB_PORT + 1)) shouldBe false
        entry.admits(Destination("192.0.2.10", DB_PORT)) shouldBe false
        EgressGuard.LOOPBACK.admits(Destination("127.0.0.1", DISCARD_PORT)) shouldBe true
        EgressGuard.LOOPBACK.admits(Destination("127.0.0.2", DISCARD_PORT)) shouldBe false
        EgressGuard.LOOPBACK.admits(Destination("127.0.0.10", DISCARD_PORT)) shouldBe false
    }

    @Test
    fun `INV-AC-22534 after the guard is removed a connect to 192_0_2_1 port 9 is not recorded`() {
        val guard = EgressGuard.install()
        guard.close()
        val before = guard.recorded()
        socket(UNROUTABLE, DISCARD_PORT)
        guard.recorded() shouldBe before
        EgressGuard.install().use { fresh -> fresh.recorded() shouldBe emptyList() }
    }

    @Test
    fun `INV-AC-22534 a second guard cannot be installed while one is installed`() {
        EgressGuard.install().use {
            assertThrows(IllegalStateException::class.java) { EgressGuard.install() }
        }
    }

    @Test
    fun `INV-AC-22534 without the agent the guard fails closed`() {
        val marker = System.getProperties()[EgressRecorder.AGENT_KEY]
        try {
            System.getProperties().remove(EgressRecorder.AGENT_KEY)
            assertThrows(AssertionError::class.java) { EgressGuard.install() }
        } finally {
            if (marker != null) System.getProperties()[EgressRecorder.AGENT_KEY] = marker
        }
    }
}
