package com.dynokore.gst.submission.application.correction

import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
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
        var nettedO2: List<String> = emptyList(),
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

                override fun nettedBySystem(originalDocUuid: String): List<String> =
                    if (originalDocUuid == "original-O") netted else emptyList()

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

    private val base = listOf(LifecycleState.ACKNOWLEDGED)

    private fun World.noSideEffects() {
        calls shouldBe emptyList()
    }

    @DisplayName(
        "INV-AC-25527 a AT_IRAS original, registered version below v, A netted into O " +
            "Refused, one AMENDED_AFTER_TRANSMISSION finding (A, v, O)",
    )
    @Test
    fun `INV-AC-25527 a`() {
        val w = World(base, listOf(a))
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Refused
        w.findings shouldBe listOf(refusedFinding(2))
        w.noSideEffects()
    }

    @DisplayName("INV-AC-25527 b SENT_OUT original, otherwise as a Refused, one finding")
    @Test
    fun `INV-AC-25527 b`() {
        val w = World(listOf(LifecycleState.SENT), listOf(a))
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Refused
        w.findings shouldBe listOf(refusedFinding(2))
        w.noSideEffects()
    }

    @DisplayName("INV-AC-25527 c1 original only READY, otherwise as a Proceed, no finding")
    @Test
    fun `INV-AC-25527 c1`() {
        val w = World(listOf(LifecycleState.READY), listOf(a))
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        w.findings shouldBe emptyList()
        w.noSideEffects()
    }

    @DisplayName("INV-AC-25527 c2 original only AWAITING_PRECEDING, otherwise as a Proceed, no finding")
    @Test
    fun `INV-AC-25527 c2`() {
        val w = World(listOf(LifecycleState.AWAITING_PRECEDING), listOf(a))
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        w.findings shouldBe emptyList()
        w.noSideEffects()
    }

    @DisplayName("INV-AC-25527 d v equal to the registered version (redelivery), otherwise as a Proceed, no finding")
    @Test
    fun `INV-AC-25527 d`() {
        val w = World(base, listOf(a), registered = 2)
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        w.findings shouldBe emptyList()
        w.noSideEffects()
    }

    @DisplayName("INV-AC-25527 e A not in nettedBySystem(O), otherwise as a Proceed, no finding")
    @Test
    fun `INV-AC-25527 e`() {
        val w = World(base, emptyList())
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Proceed
        w.findings shouldBe emptyList()
        w.noSideEffects()
    }

    @DisplayName(
        "INV-AC-25527 f case a delivered twice Refused both times, one finding stored, second record returns false",
    )
    @Test
    fun `INV-AC-25527 f`() {
        val w = World(base, listOf(a))
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Refused
        w.guard.check(a, 2) shouldBe AmendmentVerdict.Refused
        w.findings shouldBe listOf(refusedFinding(2))
        w.store.record(refusedFinding(2)) shouldBe false
        w.noSideEffects()
    }

    @DisplayName("INV-AC-25527 g look-alike A2 netted into O2 while O (not O2) is transmitted Proceed, no finding")
    @Test
    fun `INV-AC-25527 g`() {
        // O is transmitted for A, but A2 is netted into O2, whose versions are only READY.
        val w =
            World(
                listOf(LifecycleState.READY),
                listOf(a),
                originalOfA = "original-O2",
                nettedO2 = listOf("credit-note-A2"),
            )
        w.guard.check("credit-note-A2", 2) shouldBe AmendmentVerdict.Proceed
        w.findings shouldBe emptyList()
        w.noSideEffects()
    }

    @DisplayName(
        "INV-AC-25527 h recordNotPositive with NET_NOT_POSITIVE for docUuids A, A, B " +
            "two findings of kind NET_NOT_POSITIVE (version null, original null)",
    )
    @Test
    fun `INV-AC-25527 h`() {
        val w = World(emptyList(), emptyList())
        w.guard.recordNotPositive(listOf(a, a, "credit-note-B"), "NET_NOT_POSITIVE")
        w.findings shouldBe
            listOf(
                NetFinding(NetFindingKind.NET_NOT_POSITIVE, a, null, null),
                NetFinding(NetFindingKind.NET_NOT_POSITIVE, "credit-note-B", null, null),
            )
        w.noSideEffects()
    }

    @DisplayName("INV-AC-25527 i recordNotPositive with reason NOT_PRECEDING no finding")
    @Test
    fun `INV-AC-25527 i`() {
        val w = World(emptyList(), emptyList())
        w.guard.recordNotPositive(listOf(a), "NOT_PRECEDING")
        w.findings shouldBe emptyList()
        w.noSideEffects()
    }
}
