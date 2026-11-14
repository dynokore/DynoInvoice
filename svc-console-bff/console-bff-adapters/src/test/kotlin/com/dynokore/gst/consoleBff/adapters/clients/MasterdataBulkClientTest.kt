package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.resolvegroup.BulkCommand
import com.dynokore.gst.consoleBff.application.resolvegroup.BulkOutcome
import com.dynokore.gst.consoleBff.application.resolvegroup.ResolvedIdentifier
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

private class BulkSeen(
    val method: String,
    val path: String,
    val body: String,
    val authorization: String?,
    val idempotencyKey: String?,
)

/** A stub masterdata-svc: like [OwnerStub] and it also records the Idempotency-Key header. */
private class BulkStub {
    @Volatile var status = 200

    @Volatile var body: String? = null
    val seen = CopyOnWriteArrayList<BulkSeen>()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { x ->
                seen +=
                    BulkSeen(
                        x.requestMethod,
                        x.requestURI.path,
                        String(x.requestBody.readAllBytes()),
                        x.requestHeaders.getFirst("Authorization"),
                        x.requestHeaders.getFirst("Idempotency-Key"),
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

private fun items(array: tools.jackson.databind.JsonNode) = (0 until array.size()).map { array.get(it) }

/** INV-AC-21307: MasterdataBulkClient over the OwningServiceEdge and a stub masterdata-svc. */
class MasterdataBulkClientTest {
    private val stub = BulkStub()
    private val key = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val d1 = "11111111-1111-1111-1111-111111111111"
    private val d2 = "22222222-2222-2222-2222-222222222222"
    private val command =
        BulkCommand(
            "ENT-1",
            key,
            "MDM-002",
            "CP-7",
            listOf(d1, d2),
            listOf(ResolvedIdentifier("UEN", "201912345A")),
            "ev-1",
        )

    @AfterEach
    fun stop() = stub.stop()

    private fun resolve(
        registry: ResilienceRegistry = TestEdges.passThrough,
        cmd: BulkCommand = command,
    ) = CurrentEndUserToken.callWith("Bearer u-1") {
        MasterdataBulkClient(
            TestEdges.builder(),
            registry,
            TestEdges.clock,
            MockEnvironment().withProperty(OwningService.MASTERDATA.urlProperty, stub.url),
        ).resolve(cmd)
    }

    @Test
    @DisplayName("INV-AC-21307 bulk 200 gives Ok with resolvedCount and unchangedCount")
    fun okCounts() {
        stub.answer(200, """{"resolvedCount":3,"unchangedCount":1}""")
        assertEquals(OwnerReply.Ok(BulkOutcome(3, 1)), resolve())
    }

    @Test
    @DisplayName("INV-AC-21307 bulk 400, 403, 413 and 422 envelopes are relayed unchanged")
    fun refusalsRelayed() {
        for ((status, code) in listOf(400 to "SRT-001", 403 to "SRT-004", 413 to "SRT-014", 422 to "MDM-002")) {
            stub.answer(status, envelope(status, code))
            assertEquals(
                OwnerReply.Refused(OwnerRefusal(status, code, "T", "BLOCK", "MANUAL", "R", "D")),
                resolve(),
                "status $status",
            )
        }
    }

    @Test
    @DisplayName("INV-AC-21307 bulk an absent envelope member stays null")
    fun absentMemberNull() {
        stub.answer(422, envelope(422, "MDM-002", extra = ""","title":"T""""))
        assertEquals(OwnerReply.Refused(OwnerRefusal(422, "MDM-002", "T", null, null, null, null)), resolve())
    }

    @Test
    @DisplayName("INV-AC-21307 bulk 4xx unreadable or without code gives Unavailable")
    fun unreadable4xx() {
        for (raw in UNREADABLE_BODIES) {
            stub.answer(422, raw)
            assertEquals(true, resolve() is OwnerReply.Unavailable, "body=$raw")
        }
    }

    @Test
    @DisplayName("INV-AC-21307 bulk 500 and a 200 with a missing member give Unavailable")
    fun serverErrorAndMissingMember() {
        stub.answer(500, envelope(500, "MDM-002"))
        assertEquals(true, resolve() is OwnerReply.Unavailable)
        for (raw in listOf(
            null,
            "{}",
            """{"resolvedCount":3}""",
            """{"unchangedCount":1}""",
            """{"resolvedCount":"3","unchangedCount":1}""",
        )) {
            stub.answer(200, raw)
            assertEquals(true, resolve() is OwnerReply.Unavailable, "body=$raw")
        }
    }

    @Test
    @DisplayName("INV-AC-21307 bulk refused connection, timeout and open breaker give Unavailable")
    fun edgeFailures() {
        for (registry in listOf(TestEdges.connectRefused, TestEdges.timedOut, TestEdges.breakerOpen)) {
            stub.answer(200, """{"resolvedCount":3,"unchangedCount":1}""")
            assertEquals(true, resolve(registry) is OwnerReply.Unavailable)
            assertEquals(0, stub.seen.size)
        }
    }

    @Test
    @DisplayName("INV-AC-21307 bulk request has path, Idempotency-Key, token and the six body members")
    fun requestShape() {
        stub.answer(200, """{"resolvedCount":3,"unchangedCount":1}""")
        resolve()
        val sent = stub.seen.single()
        assertEquals("POST", sent.method)
        assertEquals("/internal/v1/masterdata/counterparties/bulk-resolution", sent.path)
        assertEquals(key, sent.idempotencyKey)
        assertEquals("Bearer u-1", sent.authorization)
        val tree = JsonMapper.builder().build().readTree(sent.body)
        assertEquals("ENT-1", tree.get("entityRef").stringValue())
        assertEquals("MDM-002", tree.get("errorCode").stringValue())
        assertEquals("ev-1", tree.get("evidenceRef").stringValue())
        assertEquals(
            "UEN",
            tree
                .get("resolvedIdentifiers")
                .get(0)
                .get("scheme")
                .stringValue(),
        )
        assertEquals(
            "201912345A",
            tree
                .get("resolvedIdentifiers")
                .get(0)
                .get("value")
                .stringValue(),
        )
        assertEquals(listOf(d1, d2), items(tree.get("docUuids")).map { it.stringValue() })
        val documents =
            items(tree.get("documents")).map {
                it.get("docUuid").stringValue() to
                    it.get("counterpartyRef").stringValue()
            }
        assertEquals(listOf(d1 to "CP-7", d2 to "CP-7"), documents)
        assertEquals(
            setOf("entityRef", "errorCode", "evidenceRef", "resolvedIdentifiers", "docUuids", "documents"),
            tree.propertyNames().toSet(),
        )
    }

    @Test
    @DisplayName("INV-AC-21307 bulk no request is repeated after a 4xx")
    fun noRetry() {
        for (status in listOf(400, 413, 422)) {
            stub.answer(status, envelope(status, "SRT-001"))
            resolve()
            assertEquals(1, stub.seen.size, "status $status")
        }
    }

    @Test
    @DisplayName("INV-AC-21307 bulk a key or docUuid that is no UUID is never sent")
    fun nonUuidNotSent() {
        stub.answer(200, """{"resolvedCount":3,"unchangedCount":1}""")
        assertEquals(true, resolve(cmd = command.copy(idempotencyKey = "k-1")) is OwnerReply.Unavailable)
        assertEquals(true, resolve(cmd = command.copy(docUuids = listOf("x"))) is OwnerReply.Unavailable)
        assertEquals(0, stub.seen.size)
    }
}
