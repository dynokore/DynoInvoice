package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.resolvegroup.BoundaryResult
import com.dynokore.gst.consoleBff.application.resolvegroup.SubmittedMember
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/** INV-AC-21307: SubmissionBoundaryClient over the OwningServiceEdge and a stub submission-svc. */
class SubmissionBoundaryClientTest {
    private val stub = OwnerStub()
    private val d1 = "11111111-1111-1111-1111-111111111111"
    private val d2 = "22222222-2222-2222-2222-222222222222"

    @AfterEach
    fun stop() = stub.stop()

    private fun check(registry: ResilienceRegistry = TestEdges.passThrough) =
        CurrentEndUserToken.callWith("Bearer u-1") {
            SubmissionBoundaryClient(
                TestEdges.builder(),
                registry,
                TestEdges.clock,
                MockEnvironment().withProperty(OwningService.SUBMISSION.urlProperty, stub.url),
            ).check(listOf(d1, d2))
        }

    private val ok = """{"checkedCount":2,"submittedCount":1,"submittedMembers":[{"docUuid":"$d2","state":"SENT"}]}"""

    @Test
    @DisplayName("INV-AC-21307 boundary 200 gives Ok with checkedCount, submittedCount and submittedMembers")
    fun okCounts() {
        stub.answer(200, ok)
        assertEquals(OwnerReply.Ok(BoundaryResult(2, 1, listOf(SubmittedMember(d2, "SENT")))), check())
    }

    @Test
    @DisplayName("INV-AC-21307 boundary 400, 403, 413 and 422 envelopes are relayed unchanged")
    fun refusalsRelayed() {
        for ((status, code) in listOf(400 to "SRT-001", 403 to "SRT-004", 413 to "SRT-014", 422 to "MDM-002")) {
            stub.answer(status, envelope(status, code))
            assertEquals(
                OwnerReply.Refused(OwnerRefusal(status, code, "T", "BLOCK", "MANUAL", "R", "D")),
                check(),
                "status $status",
            )
        }
    }

    @Test
    @DisplayName("INV-AC-21307 boundary an absent envelope member stays null")
    fun absentMemberNull() {
        stub.answer(413, envelope(413, "SRT-014", extra = ""","title":"T""""))
        assertEquals(OwnerReply.Refused(OwnerRefusal(413, "SRT-014", "T", null, null, null, null)), check())
    }

    @Test
    @DisplayName("INV-AC-21307 boundary 4xx unreadable or without code gives Unavailable")
    fun unreadable4xx() {
        for (raw in UNREADABLE_BODIES) {
            stub.answer(422, raw)
            assertEquals(true, check() is OwnerReply.Unavailable, "body=$raw")
        }
    }

    @Test
    @DisplayName("INV-AC-21307 boundary 500, also with an envelope, and a 200 with a missing member give Unavailable")
    fun serverErrorAndMissingMember() {
        stub.answer(500, envelope(500, "SRT-008"))
        assertEquals(true, check() is OwnerReply.Unavailable)
        for (raw in listOf(
            null,
            "{}",
            """{"checkedCount":2,"submittedCount":1}""",
            """{"checkedCount":2,"submittedMembers":[]}""",
            """{"checkedCount":2,"submittedCount":1,"submittedMembers":[{"docUuid":"$d2"}]}""",
        )) {
            stub.answer(200, raw)
            assertEquals(true, check() is OwnerReply.Unavailable, "body=$raw")
        }
    }

    @Test
    @DisplayName("INV-AC-21307 boundary refused connection, timeout and open breaker give Unavailable")
    fun edgeFailures() {
        for (registry in listOf(TestEdges.connectRefused, TestEdges.timedOut, TestEdges.breakerOpen)) {
            stub.answer(200, ok)
            assertEquals(true, check(registry) is OwnerReply.Unavailable)
            assertEquals(0, stub.seen.size)
        }
    }

    @Test
    @DisplayName("INV-AC-21307 boundary request is POST with the docUuids and the end-user token")
    fun requestShape() {
        stub.answer(200, ok)
        check()
        val sent = stub.seen.single()
        assertEquals("POST", sent.method)
        assertEquals("/internal/v1/submission/boundary-checks", sent.path)
        assertEquals("""{"docUuids":["$d1","$d2"]}""", sent.body.replace(" ", ""))
        assertEquals("Bearer u-1", sent.authorization)
    }

    @Test
    @DisplayName("INV-AC-21307 boundary no request is repeated after a 4xx")
    fun noRetry() {
        for (status in listOf(400, 413, 422)) {
            stub.answer(status, envelope(status, "SRT-001"))
            check()
            assertEquals(1, stub.seen.size, "status $status")
        }
    }
}
