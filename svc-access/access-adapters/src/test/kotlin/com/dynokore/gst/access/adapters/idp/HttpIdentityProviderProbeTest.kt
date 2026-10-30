package com.dynokore.gst.access.adapters.idp

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

class HttpIdentityProviderProbeTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @AfterEach
    fun stop() = server.stop(0)

    private fun serve(
        path: String,
        status: Int = 200,
        body: String = "{}",
        delayMillis: Long = 0,
    ) {
        server.createContext(path) { ex ->
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val bytes = body.toByteArray()
            try {
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) {
                // the client gave up
            }
        }
    }

    private fun probe(jwkSetUri: String? = null) =
        server.run {
            start()
            HttpIdentityProviderProbe(base, jwkSetUri).probe()
        }

    @Test
    fun `both answering 200 with parseable bodies succeeds`() {
        serve("/.well-known/openid-configuration", body = """{"jwks_uri":"$base/keys"}""")
        serve("/keys", body = """{"keys":[]}""")
        assertEquals(true, probe())
    }

    @Test
    fun `a configured key set address is used instead of jwks_uri`() {
        serve("/.well-known/openid-configuration")
        serve("/other", body = """{"keys":[]}""")
        assertEquals(true, probe("$base/other"))
    }

    @Test
    fun `discovery 200 but key set failing counts as failed`() {
        serve("/.well-known/openid-configuration", body = """{"jwks_uri":"$base/keys"}""")
        serve("/keys", status = 503)
        assertEquals(false, probe())
    }

    @Test
    fun `a discovery answer with status 500 counts as failed`() {
        serve("/.well-known/openid-configuration", status = 500)
        serve("/keys")
        assertEquals(false, probe("$base/keys"))
    }

    @Test
    fun `a body that does not parse counts as failed`() {
        serve("/.well-known/openid-configuration", body = "<html>")
        serve("/keys")
        assertEquals(false, probe("$base/keys"))
    }

    @Test
    fun `a key set body that does not parse counts as failed`() {
        serve("/.well-known/openid-configuration")
        serve("/keys", body = "{not json")
        assertEquals(false, probe("$base/keys"))
    }

    @Test
    fun `a discovery document without jwks_uri and no configured key set counts as failed`() {
        serve("/.well-known/openid-configuration")
        assertEquals(false, probe())
    }

    @Test
    fun `an answer after 3 seconds counts as failed within the 2 second timeout`() {
        serve("/.well-known/openid-configuration", delayMillis = 3000)
        serve("/keys")
        val start = System.nanoTime()
        val ok = probe("$base/keys")
        val elapsed = Duration.ofNanos(System.nanoTime() - start)
        assertEquals(listOf(false, true), listOf(ok, elapsed < Duration.ofMillis(3000)))
    }
}
