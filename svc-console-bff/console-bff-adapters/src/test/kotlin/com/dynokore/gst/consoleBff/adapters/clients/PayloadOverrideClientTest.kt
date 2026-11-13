package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.override.Release
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import com.dynokore.platform.serviceruntime.resilience.http.EndUserTokenPropagationInterceptor
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** One request the stub owner received. */
internal class SeenRequest(
    val method: String,
    val path: String,
    val body: String,
    val authorization: String?,
)

/** A stub owning service: answers every request with the status and body set, and records what it received. */
internal class OwnerStub {
    @Volatile var status = 200

    @Volatile var body: String? = null
    val seen = CopyOnWriteArrayList<SeenRequest>()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { x ->
                seen +=
                    SeenRequest(
                        x.requestMethod,
                        x.requestURI.path,
                        String(x.requestBody.readAllBytes()),
                        x.requestHeaders.getFirst("Authorization"),
                    )
                val bytes = body?.toByteArray()
                if (bytes != null) x.responseHeaders.add("Content-Type", "application/json")
                x.sendResponseHeaders(status, bytes?.size?.toLong() ?: -1)
                bytes?.let { x.responseBody.write(it) }
                x.close()
            }
            start()
        }
    val url = "http://127.0.0.1:${server.address.port}"

    fun answer(
        status: Int,
        body: String?,
    ) {
        this.status = status
        this.body = body
        seen.clear()
    }

    fun stop() = server.stop(0)
}

/** The edge policies of the tests: one runs the call as it is, the others give the fallback the policy would. */
internal object TestEdges {
    val passThrough =
        ResilienceRegistry {
            object : ResilientEdge {
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T = block()
            }
        }

    private fun fallback(pick: (() -> Any?, () -> Any?, () -> Any?) -> (() -> Any?)) =
        ResilienceRegistry {
            object : ResilientEdge {
                @Suppress("UNCHECKED_CAST")
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T = pick(onCallNotPermitted, onConnectFailure, onTimeout)() as T
            }
        }

    val breakerOpen = fallback { open, _, _ -> open }
    val connectRefused = fallback { _, refused, _ -> refused }
    val timedOut = fallback { _, _, timeout -> timeout }

    val clock: Clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), java.time.ZoneOffset.UTC)

    fun builder(): RestClient.Builder = RestClient.builder().requestInterceptor(EndUserTokenPropagationInterceptor())
}

/** The envelope members the owners answer a 4xx with. */
internal fun envelope(
    status: Int,
    code: String,
    extra: String = ""","title":"T","detail":"D","severity":"BLOCK","disposition":"MANUAL","remediationOwner":"R"""",
) = """{"type":"https://x/$code","status":$status,"code":"$code","correlationId":"c-1"$extra}"""

/** INV-AC-21204: PayloadOverrideClient over the OwningServiceEdge of INV-T-21174 and a stub payload-svc. */
class PayloadOverrideClientTest {
    private val stub = OwnerStub()
    private val doc = "11111111-1111-1111-1111-111111111111"

    @AfterEach
    fun stop() = stub.stop()

    private fun client(registry: ResilienceRegistry = TestEdges.passThrough) =
        PayloadOverrideClient(
            TestEdges.builder(),
            registry,
            TestEdges.clock,
            MockEnvironment().withProperty(OwningService.PAYLOAD.urlProperty, stub.url),
        )

    private fun read() = CurrentEndUserToken.callWith("Bearer u-1") { client().latestVersion(doc) }

    private fun write(registry: ResilienceRegistry = TestEdges.passThrough) =
        CurrentEndUserToken.callWith("Bearer u-1") { client(registry).override(doc, 3, "PAY-017", "because") }

    private val overridden = """{"docUuid":"$doc","version":3,"code":"PAY-017","reason":"because","overriddenBy":"u","overriddenAt":"2026-11-13T10:00:00Z"}"""
    private val pending = """{"docUuid":"$doc","version":3,"code":"PAY-017","reason":"because","requestedBy":"u","requestedAt":"2026-11-13T11:00:00Z","status":"PENDING"}"""

    private fun refused(
        status: Int,
        code: String,
    ) = OwnerReply.Refused(OwnerRefusal(status, code, "T", "BLOCK", "MANUAL", "R", "D"))

    @Test
    @DisplayName("INV-AC-21204 payload 200 gives Ok with the latest version")
    fun latestVersionOk() {
        stub.answer(200, """{"docUuid":"$doc","version":7}""")
        assertEquals(OwnerReply.Ok(7L), read())
    }

    @Test
    @DisplayName("INV-AC-21204 payload 201 gives Released(overriddenAt) and 202 gives Ok(Pending(requestedAt))")
    fun releaseAndPending() {
        stub.answer(201, overridden)
        assertEquals(OwnerReply.Ok(Release.Released(Instant.parse("2026-11-13T10:00:00Z"))), write())
        stub.answer(202, pending)
        assertEquals(OwnerReply.Ok(Release.Pending(Instant.parse("2026-11-13T11:00:00Z"))), write())
    }

    @Test
    @DisplayName("INV-AC-21204 payload 400 SRT-001, 403 SRT-004, 404 SRT-005, 409 SRT-008, 422 PAY-017 are relayed unchanged")
    fun refusalsRelayed() {
        for ((status, code) in listOf(400 to "SRT-001", 403 to "SRT-004", 404 to "SRT-005", 409 to "SRT-008", 422 to "PAY-017")) {
            stub.answer(status, envelope(status, code))
            assertEquals(refused(status, code), write(), "override $status")
            stub.answer(status, envelope(status, code))
            assertEquals(refused(status, code), read(), "read $status")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 payload an absent member stays null and none is filled")
    fun absentMembersStayNull() {
        stub.answer(422, envelope(422, "PAY-017", extra = ""","title":"T""""))
        assertEquals(OwnerReply.Refused(OwnerRefusal(422, "PAY-017", "T", null, null, null, null)), write())
    }

    @Test
    @DisplayName("INV-AC-21204 payload the status of the answer is the status of the refusal, not the envelope's member")
    fun statusFromHttp() {
        stub.answer(409, envelope(422, "SRT-008"))
        assertEquals(409, (write() as OwnerReply.Refused).refusal.status)
    }

    @Test
    @DisplayName("INV-AC-21204 payload a 4xx with an unreadable body or without a code gives Unavailable")
    fun unreadable4xx() {
        for (raw in listOf(null, "", "not json", "[1]", """{"title":"T","status":422}""", """{"code":"X"}""", """{"code":7,"title":"T"}""")) {
            stub.answer(422, raw)
            assertEquals(true, write() is OwnerReply.Unavailable, "override body=$raw")
            stub.answer(404, raw)
            assertEquals(true, read() is OwnerReply.Unavailable, "read body=$raw")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 payload a 500 gives Unavailable, also when its body is a readable envelope")
    fun serverError() {
        stub.answer(500, envelope(500, "PAY-017"))
        assertEquals(true, write() is OwnerReply.Unavailable)
        assertEquals(true, read() is OwnerReply.Unavailable)
    }

    @Test
    @DisplayName("INV-AC-21204 payload a refused connection, a timeout and an open breaker give Unavailable")
    fun edgeFailures() {
        for (registry in listOf(TestEdges.connectRefused, TestEdges.timedOut, TestEdges.breakerOpen)) {
            stub.answer(201, overridden)
            assertEquals(true, write(registry) is OwnerReply.Unavailable)
            assertEquals(true, CurrentEndUserToken.callWith("Bearer u-1") { client(registry).latestVersion(doc) } is OwnerReply.Unavailable)
            assertEquals(0, stub.seen.size, "the stub was not called")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 payload an answer that is 2xx but unreadable or of another status gives Unavailable")
    fun unreadableSuccess() {
        for ((status, raw) in listOf(201 to null, 201 to "{}", 200 to overridden, 202 to overridden, 204 to null, 201 to pending)) {
            stub.answer(status, raw)
            assertEquals(true, write() is OwnerReply.Unavailable, "override $status $raw")
        }
        for (raw in listOf(null, "{}", """{"version":"7"}""")) {
            stub.answer(200, raw)
            assertEquals(true, read() is OwnerReply.Unavailable, "read $raw")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 payload the request has the contract's method, path and body members and the end-user token")
    fun requestShape() {
        stub.answer(201, overridden)
        write()
        val sent = stub.seen.single()
        assertEquals("POST", sent.method)
        assertEquals("/internal/v1/payload/documents/$doc/versions/3/overrides", sent.path)
        assertEquals("""{"code":"PAY-017","reason":"because"}""", sent.body.replace(" ", ""))
        assertEquals("Bearer u-1", sent.authorization)
        stub.answer(200, """{"docUuid":"$doc","version":7}""")
        read()
        val get = stub.seen.single()
        assertEquals("GET", get.method)
        assertEquals("/internal/v1/payload/documents/$doc/latest-version", get.path)
        assertEquals("Bearer u-1", get.authorization)
    }

    @Test
    @DisplayName("INV-AC-21204 payload no request is repeated after a 4xx or a 5xx")
    fun noRetry() {
        for (status in listOf(409, 422, 500)) {
            stub.answer(status, envelope(status, "SRT-008"))
            write()
            assertEquals(1, stub.seen.size, "override $status")
            stub.answer(status, envelope(status, "SRT-008"))
            read()
            assertEquals(1, stub.seen.size, "read $status")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 payload no token reaches the stub when none is ambient is not invented by the client")
    fun noInventedToken() {
        stub.answer(200, """{"docUuid":"$doc","version":7}""")
        client().latestVersion(doc)
        assertEquals(null, stub.seen.single().authorization)
    }
}
