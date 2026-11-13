package com.dynokore.gst.consoleBff.application.override

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class OverrideExceptionTest {
    private val tenant = "t1"
    private val user = "u1"
    private val reason = "client confirmed"
    private val at = Instant.parse("2026-11-12T08:00:00Z")
    private val refusal = OwnerRefusal(422, "PAY-017", "Not overridable", "BLOCK", "TERMINAL", "OWNER", "detail")

    private fun row(
        id: String,
        code: String,
        state: String = "OPEN",
        doc: String = "d-$id",
        period: String = "",
    ) = OverridableRow(id, "ENT-1", doc, period, code, state)

    private val rows =
        listOf(
            row("r1", "PAY-006"),
            row("r2", "PAY-011"),
            row("r3", "PER-001", period = "2026-Q3"),
            row("r4", "PER-001"),
            row("r5", "PAY-006", doc = ""),
            row("r6", "PAY-006", state = "RESOLVED"),
            row("r7", "PAY-006", state = "OVERRIDDEN"),
            row("r8", "MDM-001"),
            row("r9", "PAY-0060"),
            row("r10", "pay-006"),
        ).associateBy { it.exceptionId }

    private class Fixture(
        rows: Map<String, OverridableRow>,
        grant: Boolean,
        val readVersion: OwnerReply<Long>,
        val readCheck: OwnerReply<String>,
        val command: OwnerReply<Release>,
    ) {
        val calls = mutableListOf<String>()
        val useCase =
            OverrideException(
                { id ->
                    calls += "find($id)"
                    rows[id]
                },
                { t, u, e ->
                    calls += "holds($t,$u,$e)"
                    grant
                },
                object : PayloadOverrides {
                    override fun latestVersion(docUuid: String): OwnerReply<Long> {
                        calls += "payload.latestVersion($docUuid)"
                        return readVersion
                    }

                    override fun override(
                        docUuid: String,
                        version: Long,
                        code: String,
                        reason: String,
                    ): OwnerReply<Release> {
                        calls += "payload.override($docUuid,$version,$code,$reason)"
                        return command
                    }
                },
                object : SubmissionOverrides {
                    override fun latestCheck(
                        entityRef: String,
                        periodRef: String,
                    ): OwnerReply<String> {
                        calls += "submission.latestCheck($entityRef,$periodRef)"
                        return readCheck
                    }

                    override fun override(
                        entityRef: String,
                        periodRef: String,
                        checkId: String,
                        reason: String,
                    ): OwnerReply<Release> {
                        calls += "submission.override($entityRef,$periodRef,$checkId,$reason)"
                        return command
                    }
                },
            )
    }

    private fun fixture(
        grant: Boolean = true,
        readVersion: OwnerReply<Long> = OwnerReply.Ok(7L),
        readCheck: OwnerReply<String> = OwnerReply.Ok("chk-9"),
        command: OwnerReply<Release> = OwnerReply.Ok(Release.Released(at)),
    ) = Fixture(rows, grant, readVersion, readCheck, command)

    private fun run(
        f: Fixture,
        id: String,
    ) = f.useCase.execute(tenant, user, id, reason)

    @Test
    fun `INV-AC-21200 unknown exception id gives NotFound and no other port is called`() {
        val f = fixture()
        run(f, "nope") shouldBe OverrideOutcome.NotFound
        f.calls shouldBe listOf("find(nope)")
    }

    @Test
    fun `INV-AC-21200 PAY-006 calls holds then latestVersion then override in order`() {
        val f = fixture()
        run(f, "r1") shouldBe OverrideOutcome.Overridden(at)
        f.calls shouldBe
            listOf(
                "find(r1)",
                "holds(t1,u1,ENT-1)",
                "payload.latestVersion(d-r1)",
                "payload.override(d-r1,7,PAY-006,client confirmed)",
            )
    }

    @Test
    fun `INV-AC-21200 PAY-011 uses its own code on the payload route`() {
        val f = fixture()
        run(f, "r2") shouldBe OverrideOutcome.Overridden(at)
        f.calls.drop(2) shouldBe
            listOf("payload.latestVersion(d-r2)", "payload.override(d-r2,7,PAY-011,client confirmed)")
    }

    @Test
    fun `INV-AC-21200 PER-001 routes to submission with the period and the checked id`() {
        val f = fixture()
        run(f, "r3") shouldBe OverrideOutcome.Overridden(at)
        f.calls shouldBe
            listOf(
                "find(r3)",
                "holds(t1,u1,ENT-1)",
                "submission.latestCheck(ENT-1,2026-Q3)",
                "submission.override(ENT-1,2026-Q3,chk-9,client confirmed)",
            )
    }

    @Test
    fun `INV-AC-21200 refused authority gives Forbidden and no owner port is called`() {
        for (id in listOf("r1", "r3")) {
            val f = fixture(grant = false)
            run(f, id) shouldBe OverrideOutcome.Forbidden
            f.calls shouldBe listOf("find($id)", "holds(t1,u1,ENT-1)")
        }
    }

    @Test
    fun `INV-AC-21200 state other than OPEN, unrouted codes and empty inputs give NotFound without owner calls`() {
        for (id in listOf("r4", "r5", "r6", "r7", "r8", "r9", "r10")) {
            val f = fixture()
            run(f, id) shouldBe OverrideOutcome.NotFound
            f.calls shouldBe listOf("find($id)", "holds(t1,u1,ENT-1)")
        }
    }

    @Test
    fun `INV-AC-21200 command answers map to outcomes`() {
        run(fixture(command = OwnerReply.Ok(Release.Pending(at))), "r1") shouldBe OverrideOutcome.PendingApproval(at)
        run(fixture(command = OwnerReply.Ok(Release.Pending(at))), "r3") shouldBe OverrideOutcome.PendingApproval(at)
        run(fixture(command = OwnerReply.Refused(refusal)), "r1") shouldBe OverrideOutcome.Relayed(refusal)
        run(fixture(command = OwnerReply.Refused(refusal)), "r3") shouldBe OverrideOutcome.Relayed(refusal)
        run(fixture(command = OwnerReply.Unavailable("down")), "r1") shouldBe OverrideOutcome.Unavailable("down")
        run(fixture(command = OwnerReply.Unavailable("down")), "r3") shouldBe OverrideOutcome.Unavailable("down")
    }

    @Test
    fun `INV-AC-21200 refused or unavailable read is relayed and the command is not called`() {
        val a = fixture(readVersion = OwnerReply.Refused(refusal))
        run(a, "r1") shouldBe OverrideOutcome.Relayed(refusal)
        a.calls.last() shouldBe "payload.latestVersion(d-r1)"
        val b = fixture(readVersion = OwnerReply.Unavailable("down"))
        run(b, "r2") shouldBe OverrideOutcome.Unavailable("down")
        b.calls.last() shouldBe "payload.latestVersion(d-r2)"
        val c = fixture(readCheck = OwnerReply.Refused(refusal))
        run(c, "r3") shouldBe OverrideOutcome.Relayed(refusal)
        c.calls.last() shouldBe "submission.latestCheck(ENT-1,2026-Q3)"
        val d = fixture(readCheck = OwnerReply.Unavailable("down"))
        run(d, "r3") shouldBe OverrideOutcome.Unavailable("down")
        d.calls.last() shouldBe "submission.latestCheck(ENT-1,2026-Q3)"
    }

    @Test
    fun `INV-AC-21200 route table has exactly three rows by equality`() {
        RouteTable.routeOf("PAY-006") shouldBe Route.PAYLOAD
        RouteTable.routeOf("PAY-011") shouldBe Route.PAYLOAD
        RouteTable.routeOf("PER-001") shouldBe Route.SUBMISSION
        listOf(
            "PAY-0060",
            "pay-006",
            "PAY-00",
            "",
            "PAY-017",
            "per-001",
        ).forEach { RouteTable.routeOf(it) shouldBe null }
    }

    @Test
    fun `INV-AC-21200 ports declare no member that writes the exception row`() {
        val targets = OverrideTargets { null }
        val payload =
            object : PayloadOverrides {
                override fun latestVersion(docUuid: String): OwnerReply<Long> = OwnerReply.Ok(1)

                override fun override(
                    docUuid: String,
                    version: Long,
                    code: String,
                    reason: String,
                ): OwnerReply<Release> = OwnerReply.Ok(Release.Released(at))
            }
        val submission =
            object : SubmissionOverrides {
                override fun latestCheck(
                    entityRef: String,
                    periodRef: String,
                ): OwnerReply<String> = OwnerReply.Ok("c")

                override fun override(
                    entityRef: String,
                    periodRef: String,
                    checkId: String,
                    reason: String,
                ): OwnerReply<Release> = OwnerReply.Ok(Release.Released(at))
            }
        targets.find("x") shouldBe null
        payload.latestVersion("d") shouldBe OwnerReply.Ok(1L)
        submission.latestCheck("e", "p") shouldBe OwnerReply.Ok("c")
        VALIDATION_OVERRIDE shouldBe "validation.override"
    }
}
