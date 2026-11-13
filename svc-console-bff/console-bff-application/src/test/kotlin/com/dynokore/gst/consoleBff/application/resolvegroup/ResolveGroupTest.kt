package com.dynokore.gst.consoleBff.application.resolvegroup

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResolveGroupTest {
    private val refusal = OwnerRefusal(422, "MDM-009", "Group refused", "BLOCK", "TERMINAL", "OWNER", "detail")
    private val ids = listOf(ResolvedIdentifier("UEN", "201912345K"))
    private val forty = (1..40).map { "d$it" }

    private fun request(
        docs: List<String> = forty,
        errorCode: String = "MDM-001",
        counterparty: String = "CTP-1",
        evidence: String = "EVD-1",
    ) = ResolveGroupRequest(errorCode, counterparty, docs, ids, evidence)

    private class Fixture(
        grant: Boolean = true,
        boundaryAnswer: (
            List<String>,
        ) -> OwnerReply<BoundaryResult> = { OwnerReply.Ok(BoundaryResult(it.size, 0, emptyList())) },
        bulkAnswer: OwnerReply<BulkOutcome> = OwnerReply.Ok(BulkOutcome(40, 0)),
    ) {
        val calls = mutableListOf<Any>()
        val useCase =
            ResolveGroup(
                { t, u, e ->
                    calls += "holds($t,$u,$e)"
                    grant
                },
                { docs ->
                    calls += "check($docs)"
                    boundaryAnswer(docs)
                },
                { command ->
                    calls += command
                    bulkAnswer
                },
            )

        fun run(
            request: ResolveGroupRequest,
            key: String = "K-1",
        ) = useCase.execute("t1", "u1", "ENT-1", key, request)
    }

    @Test
    fun `INV-AC-21290 granted 40 calls the three ports in order and resolves`() {
        val f = Fixture()
        f.run(request()) shouldBe ResolveGroupOutcome.Resolved(40, 0)
        f.calls shouldBe
            listOf(
                "holds(t1,u1,ENT-1)",
                "check($forty)",
                BulkCommand("ENT-1", "K-1", "MDM-001", "CTP-1", forty, ids, "EVD-1"),
            )
        Fixture(bulkAnswer = OwnerReply.Ok(BulkOutcome(0, 40))).run(request()) shouldBe
            ResolveGroupOutcome.Resolved(0, 40)
    }

    @Test
    fun `INV-AC-21290 a repeated docUuid is sent once to both ports`() {
        val f = Fixture()
        f.run(request(docs = forty + "d7")) shouldBe ResolveGroupOutcome.Resolved(40, 0)
        f.calls.size shouldBe 3
        f.calls[1] shouldBe "check($forty)"
        (f.calls[2] as BulkCommand).docUuids shouldBe forty
    }

    @Test
    fun `INV-AC-21290 empty, blank and 5001 requests call no port`() {
        val many = (1..5001).map { "x$it" }
        val bad =
            listOf(
                request(docs = emptyList()) to "K-1",
                request(docs = forty + " ") to "K-1",
                request(counterparty = " ") to "K-1",
                request(errorCode = "") to "K-1",
                request(evidence = " ") to "K-1",
                request() to " ",
            )
        bad.forEach { (r, k) ->
            val f = Fixture()
            (f.run(r, k) is ResolveGroupOutcome.Invalid) shouldBe true
            f.calls shouldBe emptyList()
        }
        val f = Fixture()
        f.run(request(docs = many)) shouldBe ResolveGroupOutcome.TooMany
        f.calls shouldBe emptyList()
        Fixture().run(request(docs = many.take(5000))) shouldBe ResolveGroupOutcome.Resolved(40, 0)
    }

    @Test
    fun `INV-AC-21290 a refused authority gives Forbidden and calls neither owner`() {
        val f = Fixture(grant = false)
        f.run(request()) shouldBe ResolveGroupOutcome.Forbidden
        f.calls shouldBe listOf("holds(t1,u1,ENT-1)")
    }

    @Test
    fun `INV-AC-21290 submitted members give GroupSubmitted and no bulk call`() {
        val members = listOf(SubmittedMember("d1", "SENT"), SubmittedMember("d2", "ACKNOWLEDGED"))
        val f = Fixture(boundaryAnswer = { OwnerReply.Ok(BoundaryResult(40, 2, members)) })
        f.run(request()) shouldBe ResolveGroupOutcome.GroupSubmitted(2)
        f.calls shouldBe listOf("holds(t1,u1,ENT-1)", "check($forty)")
    }

    @Test
    fun `INV-AC-21290 an inconsistent boundary answer fails closed`() {
        val m = listOf(SubmittedMember("d1", "SENT"))
        listOf(
            BoundaryResult(39, 0, emptyList()),
            BoundaryResult(40, 1, emptyList()),
            BoundaryResult(40, 0, m),
            BoundaryResult(40, 41, (1..41).map { SubmittedMember("d$it", "SENT") }),
        ).forEach { answer ->
            val f = Fixture(boundaryAnswer = { OwnerReply.Ok(answer) })
            (f.run(request()) is ResolveGroupOutcome.Unavailable) shouldBe true
            f.calls.size shouldBe 2
        }
    }

    @Test
    fun `INV-AC-21290 boundary Refused is relayed and Unavailable stays Unavailable without a bulk call`() {
        val r = Fixture(boundaryAnswer = { OwnerReply.Refused(refusal) })
        r.run(request()) shouldBe ResolveGroupOutcome.Relayed(refusal)
        r.calls.size shouldBe 2
        val u = Fixture(boundaryAnswer = { OwnerReply.Unavailable("down") })
        u.run(request()) shouldBe ResolveGroupOutcome.Unavailable("down")
        u.calls.size shouldBe 2
    }

    @Test
    fun `INV-AC-21290 bulk Refused is relayed unchanged and Unavailable stays Unavailable`() {
        Fixture(bulkAnswer = OwnerReply.Refused(refusal)).run(request()) shouldBe ResolveGroupOutcome.Relayed(refusal)
        Fixture(bulkAnswer = OwnerReply.Unavailable("down")).run(request()) shouldBe
            ResolveGroupOutcome.Unavailable("down")
    }

    @Test
    fun `INV-AC-21290 ports declare no member that writes console-bff state`() {
        val authority = ResolveGroupAuthority { _, _, _ -> true }
        val boundary = SubmissionBoundary { OwnerReply.Ok(BoundaryResult(it.size, 0, emptyList())) }
        val bulk = MasterdataBulk { OwnerReply.Ok(BulkOutcome(1, 0)) }
        authority.holds("t", "u", "e") shouldBe true
        boundary.check(listOf("a")) shouldBe OwnerReply.Ok(BoundaryResult(1, 0, emptyList()))
        bulk.resolve(BulkCommand("e", "k", "c", "p", listOf("a"), ids, "v")) shouldBe OwnerReply.Ok(BulkOutcome(1, 0))
        MASTERDATA_EXCEPTION_RESOLVE shouldBe "masterdata-exception.resolve"
    }
}
