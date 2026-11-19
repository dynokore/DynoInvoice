package com.dynokore.gst.submission.application.correction

import com.dynokore.gst.submission.application.register.NetDerivationData
import com.dynokore.gst.submission.application.register.NetDerivedCreditNote
import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The correction handler over fakes (INV-AC-25343). Calls are recorded and compared by equality. */
class CreditNoteCorrectionHandlerTest {
    private val a = "credit-note-A"
    private val o = "original-O"

    private class World(
        var versions: List<LifecycleState>,
        var declared: Boolean = false,
        var currentVersion: Int = 3,
    ) {
        val calls = mutableListOf<String>()
        val refused = mutableSetOf<String>()
        val netted = mutableSetOf<String>()

        val originals =
            object : OriginalVersionSource {
                override fun versionsOf(creditNoteDocUuid: String) = versions

                override fun originalOf(creditNoteDocUuid: String) =
                    if (versions.isEmpty()) null else OriginalRef("original-O", currentVersion)
            }
        val declarations = NettingDeclarations { declared }
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
                    if (reason == CorrectionReason.NETTED_BY_SYSTEM) netted += creditNoteDocUuid
                }

                override fun nettedBySystem(originalDocUuid: String) = netted.toList()

                override fun lift(creditNoteDocUuid: String) {
                    calls += "lift $creditNoteDocUuid"
                    netted -= creditNoteDocUuid
                }
            }
        val requests =
            NetDerivationRequestPort { original, base, notes -> calls += "request $original $base $notes" }
        val marks =
            object : NetRefusedMarks {
                override fun store(creditNoteDocUuid: String) {
                    calls += "mark $creditNoteDocUuid"
                    refused += creditNoteDocUuid
                }

                override fun isRefused(creditNoteDocUuid: String) = creditNoteDocUuid in refused
            }
        val handler = CreditNoteCorrectionHandler(originals, declarations, transitions, requests, marks)
    }

    private fun decideWith(
        versions: List<LifecycleState>,
        declared: Boolean = false,
    ): List<String> {
        val w = World(versions, declared)
        w.handler.decide(a)
        return w.calls
    }

    private val derivation = NetDerivationData(3, listOf(NetDerivedCreditNote(a, 1)))

    @Test
    fun `INV-AC-25343 READY requests one derivation and leaves the credit note unsubmitted`() {
        decideWith(listOf(LifecycleState.READY)) shouldBe listOf("request $o 3 [$a]")
    }

    @Test
    fun `INV-AC-25343 AWAITING_PRECEDING requests one derivation`() {
        decideWith(listOf(LifecycleState.AWAITING_PRECEDING)) shouldBe listOf("request $o 3 [$a]")
    }

    @Test
    fun `INV-AC-25343 ACKNOWLEDGED submits separately`() {
        decideWith(listOf(LifecycleState.ACKNOWLEDGED)) shouldBe listOf("submitSeparately $a")
    }

    @Test
    fun `INV-AC-25343 SENT and QUEUED wait`() {
        decideWith(listOf(LifecycleState.SENT)) shouldBe listOf("wait $a")
        decideWith(listOf(LifecycleState.QUEUED)) shouldBe listOf("wait $a")
    }

    @Test
    fun `INV-AC-25343 no resolved original submits as posted`() {
        decideWith(emptyList()) shouldBe listOf("submitAsPosted $a")
    }

    @Test
    fun `INV-AC-25343 nettingDeclared is NETTED_INTO_REVISION and publishes no request`() {
        decideWith(listOf(LifecycleState.READY), declared = true) shouldBe
            listOf("notSubmittable $a NETTED_INTO_REVISION")
    }

    @Test
    fun `INV-AC-25343 derived version marks NETTED_BY_SYSTEM once and a repeat changes nothing`() {
        val w = World(listOf(LifecycleState.READY))
        w.handler.onDerivedVersion(o, derivation)
        w.handler.onDerivedVersion(o, derivation)
        w.calls shouldBe listOf("notSubmittable $a NETTED_BY_SYSTEM")
    }

    @Test
    fun `INV-AC-25343 refusal stores the mark and decides again without a second request`() {
        val w = World(listOf(LifecycleState.READY))
        w.handler.onRefused(listOf(a), "NET_NOT_POSITIVE")
        w.calls shouldBe listOf("mark $a", "submitAsPosted $a")
    }

    @Test
    fun `INV-AC-25343 refusal after the original was sent waits`() {
        val w = World(listOf(LifecycleState.SENT))
        w.handler.onRefused(listOf(a), "NET_NOT_POSITIVE")
        w.calls shouldBe listOf("mark $a", "wait $a")
    }

    @Test
    fun `INV-AC-25343 derived version after the original was sent stores no mark and decides again`() {
        val w = World(listOf(LifecycleState.SENT, LifecycleState.READY))
        w.handler.onDerivedVersion(o, derivation)
        w.calls shouldBe listOf("wait $a")
        w.refused shouldBe emptySet()
        w.netted shouldBe emptySet()
    }

    @Test
    fun `INV-AC-25343 ERP revision without netDerivation lifts the credit note and decides again`() {
        val w = World(listOf(LifecycleState.READY))
        w.handler.onDerivedVersion(o, derivation)
        w.calls.clear()
        w.handler.onOriginalVersion(o, 4, null, emptySet())
        w.calls shouldBe listOf("mark $a", "lift $a", "submitAsPosted $a")
        w.netted shouldBe emptySet()
    }

    @Test
    fun `INV-AC-25343 revision that declares the credit note leaves it netted`() {
        val w = World(listOf(LifecycleState.READY))
        w.handler.onDerivedVersion(o, derivation)
        w.calls.clear()
        w.handler.onOriginalVersion(o, 4, null, setOf(a))
        w.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25343 derived version naming another credit note leaves this one unsubmitted`() {
        val w = World(listOf(LifecycleState.READY))
        w.handler.onDerivedVersion(o, NetDerivationData(3, listOf(NetDerivedCreditNote("credit-note-B", 1))))
        w.calls shouldBe listOf("notSubmittable credit-note-B NETTED_BY_SYSTEM")
        w.netted.contains(a) shouldBe false
    }
}
