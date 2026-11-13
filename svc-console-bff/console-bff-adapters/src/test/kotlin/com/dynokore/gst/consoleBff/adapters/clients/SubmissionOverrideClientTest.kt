package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.override.Release
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Instant

/** INV-AC-21204: SubmissionOverrideClient over the OwningServiceEdge of INV-T-21174 and a stub submission-svc. */
class SubmissionOverrideClientTest {
    private val stub = OwnerStub()

    @AfterEach
    fun stop() = stub.stop()

    private fun client(registry: ResilienceRegistry = TestEdges.passThrough) =
        SubmissionOverrideClient(
            TestEdges.builder(),
            registry,
            TestEdges.clock,
            MockEnvironment().withProperty(OwningService.SUBMISSION.urlProperty, stub.url),
        )

    private fun read(registry: ResilienceRegistry = TestEdges.passThrough) =
        CurrentEndUserToken.callWith("Bearer u-1") { client(registry).latestCheck("ENT-1", "2026-Q1") }

    private fun write(registry: ResilienceRegistry = TestEdges.passThrough) =
        CurrentEndUserToken.callWith("Bearer u-1") { client(registry).override("ENT-1", "2026-Q1", "chk-9", "because") }

    private val overridden = """{"entityRef":"ENT-1","periodRef":"2026-Q1","checkId":"chk-9","verdict":"BLOCK","reason":"because","overriddenBy":"u","overriddenAt":"2026-11-13T10:00:00Z"}"""
    private val pending = """{"entityRef":"ENT-1","periodRef":"2026-Q1","checkId":"chk-9","reason":"because","requestedBy":"u","requestedAt":"2026-11-13T11:00:00Z","status":"PENDING"}"""

    private fun refused(
        status: Int,
        code: String,
    ) = OwnerReply.Refused(OwnerRefusal(status, code, "T", "BLOCK", "MANUAL", "R", "D"))

    @Test
    @DisplayName("INV-AC-21204 submission 200 gives Ok with the latest check id")
    fun latestCheckOk() {
        stub.answer(200, """{"checkId":"chk-9","verdict":"BLOCK","checkedAt":"2026-11-13T09:00:00Z","approver":"a"}""")
        assertEquals(OwnerReply.Ok("chk-9"), read())
    }

    @Test
    @DisplayName("INV-AC-21204 submission 201 gives Released(overriddenAt) and 202 gives Ok(Pending(requestedAt))")
    fun releaseAndPending() {
        stub.answer(201, overridden)
        assertEquals(OwnerReply.Ok(Release.Released(Instant.parse("2026-11-13T10:00:00Z"))), write())
        stub.answer(202, pending)
        assertEquals(OwnerReply.Ok(Release.Pending(Instant.parse("2026-11-13T11:00:00Z"))), write())
    }

    @Test
    @DisplayName("INV-AC-21204 submission 400 SRT-001, 403 SRT-004, 404 SRT-005, 409 SRT-008, 422 PAY-017 are relayed unchanged")
    fun refusalsRelayed() {
        for ((status, code) in listOf(400 to "SRT-001", 403 to "SRT-004", 404 to "SRT-005", 409 to "SRT-008", 422 to "PAY-017")) {
            stub.answer(status, envelope(status, code))
            assertEquals(refused(status, code), write(), "override $status")
            stub.answer(status, envelope(status, code))
            assertEquals(refused(status, code), read(), "read $status")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 submission an absent member stays null and none is filled")
    fun absentMembersStayNull() {
        stub.answer(409, envelope(409, "SRT-008", extra = ""","title":"T""""))
        assertEquals(OwnerReply.Refused(OwnerRefusal(409, "SRT-008", "T", null, null, null, null)), write())
    }

    @Test
    @DisplayName("INV-AC-21204 submission a 4xx with an unreadable body or without a code gives Unavailable")
    fun unreadable4xx() {
        for (raw in listOf(null, "", "not json", "[1]", """{"title":"T","status":422}""", """{"code":"X"}""", """{"code":7,"title":"T"}""")) {
            stub.answer(422, raw)
            assertEquals(true, write() is OwnerReply.Unavailable, "override body=$raw")
            stub.answer(404, raw)
            assertEquals(true, read() is OwnerReply.Unavailable, "read body=$raw")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 submission a 500 gives Unavailable, also when its body is a readable envelope")
    fun serverError() {
        stub.answer(500, envelope(500, "SRT-008"))
        assertEquals(true, write() is OwnerReply.Unavailable)
        assertEquals(true, read() is OwnerReply.Unavailable)
    }

    @Test
    @DisplayName("INV-AC-21204 submission a refused connection, a timeout and an open breaker give Unavailable")
    fun edgeFailures() {
        for (registry in listOf(TestEdges.connectRefused, TestEdges.timedOut, TestEdges.breakerOpen)) {
            stub.answer(201, overridden)
            assertEquals(true, write(registry) is OwnerReply.Unavailable)
            assertEquals(true, read(registry) is OwnerReply.Unavailable)
            assertEquals(0, stub.seen.size, "the stub was not called")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 submission an answer that is 2xx but unreadable or of another status gives Unavailable")
    fun unreadableSuccess() {
        for ((status, raw) in listOf(201 to null, 201 to "{}", 200 to overridden, 202 to overridden, 204 to null, 201 to pending)) {
            stub.answer(status, raw)
            assertEquals(true, write() is OwnerReply.Unavailable, "override $status $raw")
        }
        for (raw in listOf(null, "{}", """{"checkId":7}""")) {
            stub.answer(200, raw)
            assertEquals(true, read() is OwnerReply.Unavailable, "read $raw")
        }
    }

    @Test
    @DisplayName("INV-AC-21204 submission the request has the contract's method, path and body members and the end-user token")
    fun requestShape() {
        stub.answer(201, overridden)
        write()
        val sent = stub.seen.single()
        assertEquals("POST", sent.method)
        assertEquals("/internal/v1/entities/ENT-1/periods/2026-Q1/filing-gate-overrides", sent.path)
        assertEquals("""{"checkId":"chk-9","reason":"because"}""", sent.body.replace(" ", ""))
        assertEquals("Bearer u-1", sent.authorization)
        stub.answer(200, """{"checkId":"chk-9"}""")
        read()
        val get = stub.seen.single()
        assertEquals("GET", get.method)
        assertEquals("/internal/v1/entities/ENT-1/periods/2026-Q1/readiness/latest-check", get.path)
        assertEquals("Bearer u-1", get.authorization)
    }

    @Test
    @DisplayName("INV-AC-21204 submission no request is repeated after a 4xx or a 5xx")
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
}
