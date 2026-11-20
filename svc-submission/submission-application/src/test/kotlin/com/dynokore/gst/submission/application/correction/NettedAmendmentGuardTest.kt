package com.dynokore.gst.submission.application.correction

import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The netted amendment guard over fakes (INV-AC-25527). Verdicts, findings and calls are compared by equality. */
class NettedAmendmentGuardTest {
    private val a = "credit-note-A"
    private val o = "original-O"

    private class World(
        var versions: List<LifecycleState>,
        var netted: List<String>,
        var registered: Int? = 1,
        var originalOfA: String? = "original-O",
    ) {
        val calls = mutableListOf<String>()
        val findings = mutableListOf<NetFinding>()

        val originals =
            object : OriginalVersionSource {
                override fun versionsOf(creditNoteDocUuid: String) = versions

                override fun originalOf(creditNoteDocUuid: String) = originalOfA?.let { OriginalRef(it, 3) }
            }
        val transitions =
            object : CreditNoteTransitions {
                override fun submitSeparately(creditNoteDocUuid: String) {
                    calls += "submitSeparately $creditNoteDocUuid"
                }

                override fun submitAsPosted(creditNoteDocUuid: String) {
                    calls += "submitAsPosted $creditNoteDocUuid"
                }

                override fun wait(creditNoteDocUuid: String) {
                    calls += "wait $creditNoteDocUuid"
                }

                override fun notSubmittable(
                    creditNoteDocUuid: String,
                    reason: CorrectionReason,
                ) {
                    calls += "notSubmittable $creditNoteDocUuid $reason"
                }

                override fun nettedBySystem(originalDocUuid: String) = if (originalDocUuid == "original-O") netted else emptyList()

                override fun lift(creditNoteDocUuid: String) {
                    calls += "lift $creditNoteDocUuid"
                }
            }
        val creditNoteVersions = CreditNoteVersions { registered }
        val store =
            NetFindings { finding ->
                if (findings.any { it.sameKey(finding) }) {
                    false
                } else {
                    findings += finding
                    true
                }
            }
        val guard = NettedAmendmentGuard(originals, transitions, creditNoteVersions, store)

        private fun NetFinding.sameKey(o: NetFinding) =
            kind == o.kind && creditNoteDocUuid == o.creditNoteDocUuid && creditNoteVersion == o.creditNoteVersion
    }

    private fun refusedFinding(version: Int) = NetFinding(NetFindingKind.AMENDED_AFTER_TRANSMISSION, a, version, o)

    @Test
    fun `INV-AC-25527 (a) amended after the original was at IRAS is refused and recorded`() {
        val w = World(listOf(LifecycleState.ACKNOWLEDGED), listOf(a))
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Refused
        w.findings shouldBe listOf(refusedFinding(2))
        w.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25527 (b) amended after the original was sent out is refused and recorded`() {
        val w = World(listOf(LifecycleState.SENT), listOf(a))
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Refused
        w.findings shouldBe listOf(refusedFinding(2))
        w.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25527 (c) original only READY or AWAITING_PRECEDING proceeds without a finding`() {
        listOf(LifecycleState.READY, LifecycleState.AWAITING_PRECEDING).forEach {
            val w = World(listOf(it), listOf(a))
            w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
            w.findings shouldBe emptyList()
            w.calls shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-25527 (d) a redelivery of the registered version proceeds without a finding`() {
        val w = World(listOf(LifecycleState.ACKNOWLEDGED), listOf(a), registered = 2)
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        w.findings shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25527 (e) a credit note not netted into the original proceeds without a finding`() {
        val w = World(listOf(LifecycleState.ACKNOWLEDGED), emptyList())
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        w.findings shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25527 (f) delivered twice records one finding and refuses both times`() {
        val w = World(listOf(LifecycleState.ACKNOWLEDGED), listOf(a))
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Refused
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Refused
        w.findings shouldBe listOf(refusedFinding(2))
        w.store.record(refusedFinding(2)) shouldBe false
    }

    @Test
    fun `INV-AC-25527 look-alike a credit note netted into another original proceeds`() {
        val w = World(listOf(LifecycleState.ACKNOWLEDGED), listOf(a), originalOfA = "original-P")
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        w.findings shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25527 no registered version or no resolved original proceeds`() {
        val w = World(listOf(LifecycleState.ACKNOWLEDGED), listOf(a), registered = null)
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        val x = World(emptyList(), listOf(a), originalOfA = null)
        x.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        (w.findings + x.findings) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25527 recordNotPositive with NET_NOT_POSITIVE records one finding per distinct credit note`() {
        val w = World(emptyList(), emptyList())
        w.guard.recordNotPositive(listOf(a, "credit-note-B", a), "NET_NOT_POSITIVE")
        w.findings shouldBe
            listOf(
                NetFinding(NetFindingKind.NET_NOT_POSITIVE, a, null, null),
                NetFinding(NetFindingKind.NET_NOT_POSITIVE, "credit-note-B", null, null),
            )
        w.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25527 recordNotPositive with another reason records nothing`() {
        val w = World(emptyList(), emptyList())
        w.guard.recordNotPositive(listOf(a), "NOT_PRECEDING")
        w.guard.recordNotPositive(listOf(a), "net_not_positive")
        w.findings shouldBe emptyList()
    }
}
