package com.dynokore.gst.consoleBff.adapters.clients

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.consoleBff.application.session.AccessAnswer
import com.dynokore.gst.consoleBff.application.session.ContentMode
import com.dynokore.gst.consoleBff.application.session.OpenOperatorSessionResult
import com.dynokore.gst.consoleBff.application.session.OperatorSession
import com.dynokore.gst.consoleBff.application.session.OperatorSessionUseCase
import com.dynokore.gst.consoleBff.application.session.SessionRow
import com.dynokore.gst.consoleBff.application.session.SessionStore
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** INV-AC-23363: the client of `openOperatorSession` against a stub access-svc; the token never reaches a log. */
class AccessOperatorSessionClientTest {
    private class Seen(
        val method: String,
        val path: String,
        val body: String,
        val headers: Map<String, String>,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    @Volatile private var status = 200

    @Volatile private var reply: String = ""
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { x ->
                seen +=
                    Seen(
                        x.requestMethod,
                        x.requestURI.path,
                        String(x.requestBody.readAllBytes()),
                        x.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                    )
                val bytes = reply.toByteArray()
                x.responseHeaders.add("Content-Type", "application/json")
                x.sendResponseHeaders(status, bytes.size.toLong())
                x.responseBody.write(bytes)
                x.close()
            }
            start()
        }
    private val secret = "SECRET-OPERATOR-TOKEN-4155"
    private val tenant = "22222222-2222-2222-2222-222222222222"
    private var counter = 0L
    private val client =
        AccessOperatorSessionClient(RestClient.builder(), "http://127.0.0.1:${server.address.port}", {
            UUID(
                counter + 1,
                counter++ + 1,
            )
        })
    private val logs = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private var level: Level? = null

    @BeforeEach
    fun capture() {
        level = root.level
        root.level = Level.TRACE
        logs.start()
        root.addAppender(logs)
    }

    @AfterEach
    fun stop() {
        root.detachAppender(logs)
        root.level = level
        server.stop(0)
    }

    private fun ok(
        mode: String = "MASKED",
        token: String = secret,
        expiresAt: String = "2026-11-15T10:15:00Z",
    ) = """{"token":"$token","expiresAt":"$expiresAt","contentMode":"$mode"}"""

    private fun answer(
        code: Int,
        body: String,
    ): AccessAnswer {
        status = code
        reply = body
        return client.open(tenant, "CASE-77")
    }

    private fun logged() = logs.list.joinToString("\n") { it.formattedMessage + it.throwableProxy?.message.orEmpty() }

    @Test
    fun `INV-AC-23363 the open posts tenantRef and caseId only with a fresh Idempotency-Key and no other header`() {
        answer(200, ok())
        answer(200, ok())
        seen.map { it.method } shouldBe listOf("POST", "POST")
        seen.map { it.path }.toSet() shouldBe setOf("/internal/v1/access/operator-sessions")
        seen[0].body shouldBe """{"tenantRef":"$tenant","caseId":"CASE-77"}"""
        seen[0].headers["idempotency-key"] shouldBe UUID(1, 1).toString()
        seen[1].headers["idempotency-key"] shouldBe UUID(2, 2).toString()
        seen[0].headers.containsKey("authorization") shouldBe false
    }

    @Test
    fun `INV-AC-23363 the answer carries token, expiresAt and contentMode`() {
        answer(200, ok("ELEVATED")) shouldBe
            AccessAnswer.Opened(secret, Instant.parse("2026-11-15T10:15:00Z"), ContentMode.ELEVATED)
    }

    @Test
    fun `INV-AC-23363 a contentMode that is neither MASKED nor ELEVATED is stored as MASKED`() {
        for (mode in listOf("FUTURE", "", "elevated", "Masked")) {
            (answer(200, ok(mode)) as AccessAnswer.Opened).contentMode shouldBe ContentMode.MASKED
        }
        (answer(200, """{"token":"$secret","expiresAt":"2026-11-15T10:15:00Z"}""") as AccessAnswer.Opened)
            .contentMode shouldBe ContentMode.MASKED
    }

    @Test
    fun `INV-AC-23363 403, 404 and 400 are refusals and a 5xx or an unusable answer is a failure`() {
        answer(403, """{"title":"no"}""") shouldBe AccessAnswer.Refused(403)
        answer(404, """{"title":"no"}""") shouldBe AccessAnswer.Refused(404)
        answer(400, """{"title":"no"}""") shouldBe AccessAnswer.Refused(400)
        answer(503, """{"title":"down"}""") shouldBe AccessAnswer.Failed
        answer(200, "not json $secret") shouldBe AccessAnswer.Failed
        answer(200, ok(expiresAt = "yesterday")) shouldBe AccessAnswer.Failed
        answer(200, """{"expiresAt":"2026-11-15T10:15:00Z"}""") shouldBe AccessAnswer.Failed
        answer(200, ok(token = "t".repeat(8193))) shouldBe AccessAnswer.Failed
        (answer(200, ok(token = "t".repeat(8192))) is AccessAnswer.Opened) shouldBe true
    }

    @Test
    fun `INV-AC-23363 the path equals the one of the generated operation`() {
        AccessOperatorSessionClient.PATH shouldBe "/internal/v1/access/operator-sessions"
    }

    private class OneRowStore(
        var row: SessionRow?,
    ) : SessionStore {
        override fun find(hash: String) = row

        override fun touch(
            hash: String,
            now: Instant,
        ) = Unit

        override fun delete(hash: String) {
            row = null
        }

        override fun attachOperatorSession(
            hash: String,
            operator: OperatorSession,
        ): Boolean {
            row = row?.copy(operator = operator)
            return row != null
        }
    }

    @Test
    fun `INV-AC-23363 the log of an open, a failed open and an expiry holds no substring equal to the token`() {
        val t0 = Instant.parse("2026-11-15T10:00:00Z")
        val store = OneRowStore(SessionRow("h", "p1", tenant, 1, t0, t0))
        var now = t0
        val clock =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId?) = this

                override fun instant() = now
            }
        val use = OperatorSessionUseCase(store, client, clock)
        status = 200
        reply = ok()
        use.open("h", tenant, "CASE-77") shouldBe
            OpenOperatorSessionResult.Opened(Instant.parse("2026-11-15T10:15:00Z"), ContentMode.MASKED)
        status = 500
        reply = """{"detail":"echo $secret"}"""
        use.open("h", tenant, "CASE-78") shouldBe OpenOperatorSessionResult.Unavailable
        status = 200
        reply = "garbage $secret"
        use.open("h", tenant, "CASE-79") shouldBe OpenOperatorSessionResult.Unavailable
        now = Instant.parse("2026-11-15T10:15:00Z")
        use.current("h") shouldBe null
        use.toString().contains(secret) shouldBe false
        store.row.toString().contains(secret) shouldBe false
        logs.list.isNotEmpty() shouldBe true
        logged().contains(secret) shouldBe false
    }
}
