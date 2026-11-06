package com.dynokore.gst.payload.application.override

import com.dynokore.gst.payload.domain.override.OverrideStatus
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class OverrideUseCasesTest {
    private val d1 = UUID.randomUUID()
    private val d2 = UUID.randomUUID()
    private val versions = FakeVersions()
    private val findings = FakeFindings()
    private val store = FakeStore()
    private val audit = RecordingAudit()
    private var authority = FakeAuthority(setOf("A1", "A2"))
    private var decisions: ReleaseDecisions = FakeDecisions()

    private fun request() = RequestOverride(versions, authority, findings, decisions, store, audit)

    private fun approve() = ApproveOverride(versions, authority, decisions, store, audit)

    private fun read() = ReadOverrides(versions, authority, store)

    private fun overridable(
        doc: UUID,
        version: Int = 1,
        code: String = "PAY-011",
        total: String = "10.00",
    ) {
        versions.put(doc, version, total)
        findings.put(doc, version, code, FindingDisposition.OVERRIDABLE)
    }

    private fun ask(
        user: String,
        doc: UUID,
        version: Int = 1,
        code: String = "PAY-011",
        reason: String? = "register lag",
    ) = request().execute(user, doc, version, code, reason)

    @Test
    fun `INV-AC-1551 a missing or empty reason is refused and nothing is stored`() {
        overridable(d1)
        ask("A1", d1, reason = "") shouldBe OverrideResult.BadRequest
        ask("A1", d1, reason = null) shouldBe OverrideResult.BadRequest
        ask("A1", d1, reason = "   ") shouldBe OverrideResult.BadRequest
        store.rows shouldBe emptyMap()
        audit.entries shouldBe emptyList()
        ask("A1", d1).shouldBeInstanceOf<OverrideResult.Released>()
        audit.entries.map { it.reason } shouldBe listOf("register lag", "register lag")
    }

    @Test
    fun `INV-AC-1584 MANUAL and TERMINAL codes and look-alikes are refused and nothing is stored`() {
        versions.put(d1, 1, "10.00")
        findings.put(d1, 1, "PAY-001", FindingDisposition.REFUSED)
        findings.put(d1, 1, "PAY-008", FindingDisposition.REFUSED)
        for (code in listOf("PAY-001", "PAY-008", "PAY-0017", "pay-001")) {
            ask("A1", d1, code = code) shouldBe OverrideResult.NotOverridable
        }
        store.rows shouldBe emptyMap()
        audit.entries shouldBe emptyList()
        decisions.shouldBeInstanceOf<FakeDecisions>().calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-2279 with nothing set one approver releases an OVERRIDABLE code and a TERMINAL one is refused`() {
        overridable(d1)
        versions.put(d2, 1, "10.00")
        findings.put(d2, 1, "PAY-008", FindingDisposition.REFUSED)
        ask("A1", d1).shouldBeInstanceOf<OverrideResult.Released>()
        ask("A1", d2, code = "PAY-008") shouldBe OverrideResult.NotOverridable
        store.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-9561 a second approver is needed above the amount only`() {
        decisions = FakeDecisions(BigDecimal("1000.00"))
        overridable(d1, total = "1000.00")
        overridable(d2, total = "1000.01")
        val results =
            listOf(
                ask("A1", d1).label(),
                ask("A1", d2).label(),
                approve().execute("A1", d2, 1, "PAY-011").label(),
                approve().execute("A2", d2, 1, "PAY-011").label(),
            )
        results shouldBe listOf("released", "waiting", "refused", "released")
        // A1 again through approve left the row waiting; A2 released it
        store.find(d2, 1, "PAY-011")!!.releasedBy shouldBe "A2"
    }

    @Test
    fun `INV-AC-9561 the answer after the refused approval is still waiting`() {
        decisions = FakeDecisions(BigDecimal("1000.00"))
        overridable(d2, total = "1000.01")
        ask("A1", d2).shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        approve().execute("A1", d2, 1, "PAY-011") shouldBe OverrideResult.Forbidden
        store.find(d2, 1, "PAY-011")!!.status shouldBe OverrideStatus.PENDING
        ask("A1", d2).shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
    }

    @Test
    fun `INV-AC-11514 an override applies to one version and the next version has no row`() {
        overridable(d1, version = 1)
        overridable(d1, version = 2)
        ask("A1", d1, version = 1, reason = "").shouldBeInstanceOf<OverrideResult.BadRequest>()
        store.rows shouldBe emptyMap()
        ask("A1", d1, version = 1).shouldBeInstanceOf<OverrideResult.Released>()
        store.find(d1, 2, "PAY-011") shouldBe null
        authority = FakeAuthority(emptySet())
        ask("A3", d1, version = 2) shouldBe OverrideResult.Forbidden
        store.find(d1, 2, "PAY-011") shouldBe null
    }

    @Test
    fun `a code stored for version 1 and asked for version 2 is refused`() {
        overridable(d1, version = 1)
        versions.put(d1, 2, "10.00")
        ask("A1", d1, version = 2) shouldBe OverrideResult.NotOverridable
        ask("A1", d2) shouldBe OverrideResult.NotOverridable // an unknown version
        store.rows shouldBe emptyMap()
    }

    @Test
    fun `a caller without the permission is refused and nothing is stored`() {
        overridable(d1)
        ask("X", d1) shouldBe OverrideResult.Forbidden
        store.rows shouldBe emptyMap()
        audit.entries shouldBe emptyList()
        authority.asked.map { it.second }.toSet() shouldBe setOf("validation.override")
    }

    @Test
    fun `a denied decision is refused with nothing stored`() {
        decisions = FakeDecisions(answer = { ReleaseOutcome.Denied("NOT_OVERRIDABLE") })
        overridable(d1)
        ask("A1", d1) shouldBe OverrideResult.Forbidden
        store.rows shouldBe emptyMap()
    }

    @Test
    fun `a repeat reports the recorded override unchanged and writes no audit entry`() {
        overridable(d1)
        val first = ask("A1", d1)
        val count = audit.entries.size
        ask("A2", d1, reason = "other") shouldBe first
        audit.entries.size shouldBe count
    }

    @Test
    fun `audit counts follow the request, the release and a refused approval`() {
        decisions = FakeDecisions(BigDecimal("1.00"))
        overridable(d2, total = "5.00")
        ask("A1", d2)
        audit.entries shouldBe listOf(RecordingAudit.Entry("requested", "A1", "A1", "register lag"))
        approve().execute("A1", d2, 1, "PAY-011") shouldBe OverrideResult.Forbidden
        audit.entries.size shouldBe 1
        approve().execute("A2", d2, 1, "PAY-011").shouldBeInstanceOf<OverrideResult.Released>()
        audit.entries.map { it.kind } shouldBe listOf("requested", "released")
        audit.entries[1] shouldBe RecordingAudit.Entry("released", "A2", "A1", "register lag")
    }

    @Test
    fun `a release in one step writes one requested and one released entry by the same user`() {
        overridable(d1)
        ask("A1", d1)
        audit.entries.map { it.kind to it.actor } shouldBe listOf("requested" to "A1", "released" to "A1")
    }

    @Test
    fun `approve is not found for no pending row, a released row and an unknown version`() {
        overridable(d1)
        approve().execute("A2", d1, 1, "PAY-011") shouldBe OverrideResult.NotFound
        ask("A1", d1)
        approve().execute("A2", d1, 1, "PAY-011") shouldBe OverrideResult.NotFound
        approve().execute("A2", d2, 1, "PAY-011") shouldBe OverrideResult.NotFound
    }

    @Test
    fun `approve is forbidden without the permission`() {
        decisions = FakeDecisions(BigDecimal("1.00"))
        overridable(d1, total = "5.00")
        ask("A1", d1)
        approve().execute("X", d1, 1, "PAY-011") shouldBe OverrideResult.Forbidden
        store.find(d1, 1, "PAY-011")!!.status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `approve is forbidden when the decision is a DENY or awaits yet another approver`() {
        decisions = FakeDecisions(BigDecimal("1.00"))
        overridable(d1, total = "5.00")
        ask("A1", d1)
        decisions = FakeDecisions(answer = { ReleaseOutcome.Denied("X") })
        approve().execute("A2", d1, 1, "PAY-011") shouldBe OverrideResult.Forbidden
        decisions = FakeDecisions(answer = { ReleaseOutcome.AwaitsSecond })
        approve().execute("A2", d1, 1, "PAY-011") shouldBe OverrideResult.Forbidden
        store.find(d1, 1, "PAY-011")!!.status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `a lost conditional update answers not found`() {
        decisions = FakeDecisions(BigDecimal("1.00"))
        overridable(d1, total = "5.00")
        ask("A1", d1)
        val racing =
            object : OverrideStore by store {
                override fun release(
                    docUuid: UUID,
                    version: Int,
                    code: String,
                    releasedBy: String,
                ): StoredOverride? = null
            }
        ApproveOverride(versions, authority, decisions, racing, audit).execute("A2", d1, 1, "PAY-011") shouldBe
            OverrideResult.NotFound
        audit.entries.size shouldBe 1
    }

    @Test
    fun `the read lists one version, needs the permission and an unknown version is not found`() {
        overridable(d1)
        overridable(d1, version = 2)
        ask("A1", d1, version = 1)
        ask("A1", d1, version = 2)
        read()
            .execute(
                "A1",
                d1,
                1,
                null,
                10,
            ).shouldBeInstanceOf<OverrideResult.Listed>()
            .entries
            .map { it.version } shouldBe
            listOf(1)
        read().execute("X", d1, 1, null, 10) shouldBe OverrideResult.Forbidden
        read().execute("A1", d2, 1, null, 10) shouldBe OverrideResult.NotFound
    }

    private fun OverrideResult.label() =
        when (this) {
            is OverrideResult.Released -> "released"
            is OverrideResult.AwaitsSecond -> "waiting"
            else -> "refused"
        }
}
