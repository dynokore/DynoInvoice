package com.dynokore.gst.submission.domain.transitions

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT_UNCONFIRMED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SUPERSEDED
import com.dynokore.gst.submission.domain.transitions.Event.AcknowledgementReceived
import com.dynokore.gst.submission.domain.transitions.Event.ApAccepted
import com.dynokore.gst.submission.domain.transitions.Event.Rejected
import com.dynokore.gst.submission.domain.transitions.Event.TimerTick
import com.dynokore.gst.submission.domain.transitions.Signal.Anomaly
import com.dynokore.gst.submission.domain.transitions.Signal.Raise
import com.dynokore.gst.submission.domain.transitions.Signal.RecordAcknowledgement
import com.dynokore.gst.submission.domain.transitions.Signal.Refused
import com.dynokore.gst.submission.domain.transitions.Signal.Resend
import com.dynokore.gst.submission.domain.transitions.Signal.StartAckTimer
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant

class VersionTransitionsTest {
    private val base = Instant.parse("2026-04-18T09:00:00Z")
    private val second = Duration.ofSeconds(1)

    private fun at(minutes: Long) = base.plus(Duration.ofMinutes(minutes))

    private fun v(
        n: Int,
        state: LifecycleState,
        ack: String? = null,
    ) = VersionRecord(n, state, ack)

    private fun tick(
        now: Instant,
        t1: Long = 10,
        t2: Long = 30,
        latest: Long = 50,
        due: Long = 100,
        resends: Int = 0,
        s5: Boolean = false,
        s10: Boolean = false,
        version: Int = 1,
    ) = TimerTick(version, now, at(t1), at(t2), at(latest), at(due), resends, s5, s10)

    private fun states(
        list: List<VersionRecord>,
        d: Decision,
    ): List<LifecycleState> = list.map { r -> d.transitions.firstOrNull { it.version == r.number }?.to ?: r.state }

    private val reasons = listOf(RejectionReason("PINT-001", "IBT-001", "missing"))

    // ---- INV-AC-1851, INV-AC-13076

    @Test
    fun `INV-AC-1851 INV-AC-13076 AP success on a queued version is SENT with the timer and not ACKNOWLEDGED`() {
        val d = VersionTransitions.decide(listOf(v(1, QUEUED)), ApAccepted(1))
        d.transitions shouldBe listOf(Transition(1, QUEUED, SENT))
        d.signals shouldBe listOf(StartAckTimer(1))
    }

    @Test
    fun `INV-AC-1851 INV-AC-13076 an acknowledgement id moves a SENT version to ACKNOWLEDGED`() {
        val d = VersionTransitions.decide(listOf(v(1, SENT)), AcknowledgementReceived(1, "ACK-1"))
        d.transitions shouldBe listOf(Transition(1, SENT, ACKNOWLEDGED))
        d.signals shouldBe listOf(RecordAcknowledgement(1, "ACK-1"))
    }

    @Test
    fun `INV-AC-1851 INV-AC-13076 a blank or whitespace-only id is refused and changes nothing`() {
        for (id in listOf("", "   ", "\t\n")) {
            val d = VersionTransitions.decide(listOf(v(1, SENT)), AcknowledgementReceived(1, id))
            d shouldBe Decision(signals = listOf(Refused(RefusalReason.NO_ACKNOWLEDGEMENT_ID)))
        }
    }

    @Test
    fun `INV-AC-1851 INV-AC-13076 a late acknowledgement after SUB-005 still gives ACKNOWLEDGED (INV-REQ-630)`() {
        val d = VersionTransitions.decide(listOf(v(1, SENT_UNCONFIRMED)), AcknowledgementReceived(1, "ACK-1"))
        d.transitions shouldBe listOf(Transition(1, SENT_UNCONFIRMED, ACKNOWLEDGED))
        d.signals shouldBe listOf(RecordAcknowledgement(1, "ACK-1"))
    }

    @Test
    fun `INV-AC-1851 INV-AC-13076 no other event targets ACKNOWLEDGED in any of the 18 states`() {
        for (s in LifecycleState.entries) {
            val list = listOf(v(1, s, "ACK-1"))
            val events =
                listOf(
                    ApAccepted(1),
                    Rejected(1, RejectionSource.IRAS, reasons),
                    Rejected(1, RejectionSource.AP, emptyList()),
                    tick(at(1000), s5 = false, s10 = false),
                    tick(at(1000), resends = 1),
                )
            for (e in events) {
                VersionTransitions.decide(list, e).transitions.none { it.to == ACKNOWLEDGED } shouldBe true
            }
        }
    }

    // ---- INV-AC-613, INV-AC-13073

    @Test
    fun `INV-AC-613 INV-AC-13073 AP success of v2 leaves v1 ACKNOWLEDGED`() {
        val list = listOf(v(1, ACKNOWLEDGED, "ACK-1"), v(2, QUEUED))
        val d = VersionTransitions.decide(list, ApAccepted(2))
        d.transitions shouldBe listOf(Transition(2, QUEUED, SENT))
        states(list, d) shouldBe listOf(ACKNOWLEDGED, SENT)
    }

    @Test
    fun `INV-AC-613 INV-AC-13073 acknowledging v2 supersedes the acknowledged v1`() {
        val list = listOf(v(1, ACKNOWLEDGED, "ACK-1"), v(2, SENT))
        val d = VersionTransitions.decide(list, AcknowledgementReceived(2, "ACK-2"))
        states(list, d) shouldBe listOf(SUPERSEDED, ACKNOWLEDGED)
        d.signals shouldBe listOf(RecordAcknowledgement(2, "ACK-2"))
    }

    @Test
    fun `INV-AC-613 INV-AC-13073 a late acknowledgement of v1 is itself superseded and v2 is unchanged`() {
        val list = listOf(v(1, SENT), v(2, ACKNOWLEDGED, "ACK-2"))
        val d = VersionTransitions.decide(list, AcknowledgementReceived(1, "ACK-1"))
        d.transitions shouldBe listOf(Transition(1, SENT, SUPERSEDED))
        d.signals shouldBe listOf(RecordAcknowledgement(1, "ACK-1"))
        states(list, d) shouldBe listOf(SUPERSEDED, ACKNOWLEDGED)
    }

    @Test
    fun `INV-AC-613 INV-AC-13073 after any decision at most one version is ACKNOWLEDGED`() {
        val events = { n: Int ->
            listOf(
                ApAccepted(n),
                AcknowledgementReceived(n, "ACK-X"),
                Rejected(n, RejectionSource.IRAS, reasons),
                tick(at(1000), version = n),
            )
        }
        for (s1 in LifecycleState.entries) {
            for (s2 in LifecycleState.entries) {
                if (s1 == ACKNOWLEDGED && s2 == ACKNOWLEDGED) continue // the input itself is invalid
                val list = listOf(v(1, s1, "ACK-1"), v(2, s2, "ACK-2"))
                for (n in 1..2) {
                    for (e in events(n)) {
                        val after = states(list, VersionTransitions.decide(list, e))
                        (after.count { it == ACKNOWLEDGED } <= 1) shouldBe true
                    }
                }
            }
        }
    }

    // ---- INV-AC-608, INV-AC-13074

    @Test
    fun `INV-AC-608 INV-AC-13074 an IRAS rejection gives REJECTED with the reasons in order and SUB-004`() {
        val given =
            listOf(
                RejectionReason("PINT-009", null, " zeta "),
                RejectionReason("PINT-001", "IBT-001", "missing"),
                RejectionReason("PINT-005", "IBT-002", "alpha"),
            )
        val d = VersionTransitions.decide(listOf(v(1, SENT)), Rejected(1, RejectionSource.IRAS, given))
        d.transitions shouldBe listOf(Transition(1, SENT, REJECTED))
        d.reasons shouldBe given
        d.signals shouldBe listOf(Raise(RaisedCode.SUB_004, 1))
        RaisedCode.SUB_004.code shouldBe "SUB-004"
    }

    @Test
    fun `INV-AC-608 INV-AC-13074 an AP rejection raises SUB-003 and an empty reason list stays empty`() {
        val d = VersionTransitions.decide(listOf(v(1, SENT)), Rejected(1, RejectionSource.AP, reasons))
        d.signals shouldBe listOf(Raise(RaisedCode.SUB_003, 1))
        RaisedCode.SUB_003.code shouldBe "SUB-003"
        val e = VersionTransitions.decide(listOf(v(1, SENT)), Rejected(1, RejectionSource.IRAS, emptyList()))
        e.reasons shouldBe emptyList()
        e.transitions shouldBe listOf(Transition(1, SENT, REJECTED))
    }

    @Test
    fun `INV-AC-608 INV-AC-13073 a REJECTED v1 stays REJECTED when v2 is acknowledged`() {
        val list = listOf(v(1, REJECTED), v(2, QUEUED))
        val d = VersionTransitions.decide(list, AcknowledgementReceived(2, "ACK-2"))
        states(list, d) shouldBe listOf(REJECTED, ACKNOWLEDGED)
    }

    @Test
    fun `INV-AC-608 INV-AC-13074 rejecting v2 leaves the acknowledged v1 ACKNOWLEDGED`() {
        val list = listOf(v(1, ACKNOWLEDGED, "ACK-1"), v(2, SENT))
        val d = VersionTransitions.decide(list, Rejected(2, RejectionSource.IRAS, reasons))
        d.transitions shouldBe listOf(Transition(2, SENT, REJECTED))
        states(list, d) shouldBe listOf(ACKNOWLEDGED, REJECTED)
    }

    // ---- INV-AC-665, INV-AC-13075

    @Test
    fun `INV-AC-665 INV-AC-13075 SENT is silent before effective1, resends at it, SUB-005 at effective2`() {
        val list = listOf(v(1, SENT))
        VersionTransitions.decide(list, tick(at(10).minus(second))) shouldBe Decision()
        val one = VersionTransitions.decide(list, tick(at(10)))
        one.transitions shouldBe listOf(Transition(1, SENT, SENT_UNCONFIRMED))
        one.signals shouldBe listOf(Resend(1))
        val two = VersionTransitions.decide(list, tick(at(30), resends = 1))
        two.transitions shouldBe emptyList()
        two.signals shouldBe listOf(Raise(RaisedCode.SUB_005, 1))
        VersionTransitions.decide(list, tick(at(30).minus(second), resends = 1)) shouldBe Decision()
        RaisedCode.SUB_005.code shouldBe "SUB-005"
    }

    @Test
    fun `INV-AC-667 INV-AC-13075 t1 resends once and t2 raises SUB-005 without a resend`() {
        val sent = VersionTransitions.decide(listOf(v(1, SENT)), tick(at(10)))
        sent.signals shouldBe listOf(Resend(1))
        val afterT1 = listOf(v(1, SENT_UNCONFIRMED))
        val d = VersionTransitions.decide(afterT1, tick(at(30), resends = 1))
        d shouldBe Decision(signals = listOf(Raise(RaisedCode.SUB_005, 1)))
    }

    @Test
    fun `INV-AC-667 INV-AC-13075 SUB-005 is raised at latestDespatchAt when t2At is later and not one second later`() {
        val list = listOf(v(1, SENT_UNCONFIRMED))
        val before = tick(at(40).minus(second), t2 = 60, latest = 40, resends = 1)
        VersionTransitions.decide(list, before) shouldBe Decision()
        val d = VersionTransitions.decide(list, tick(at(40), t2 = 60, latest = 40, resends = 1))
        d.signals shouldBe listOf(Raise(RaisedCode.SUB_005, 1))
    }

    @Test
    fun `INV-AC-667 INV-AC-13075 latestDespatchAt before t1At gives resend and SUB-005 in one tick`() {
        val d = VersionTransitions.decide(listOf(v(1, SENT)), tick(at(20), t1 = 30, t2 = 40, latest = 20))
        d.transitions shouldBe listOf(Transition(1, SENT, SENT_UNCONFIRMED))
        d.signals shouldBe listOf(Resend(1), Raise(RaisedCode.SUB_005, 1))
    }

    @Test
    fun `INV-AC-667 INV-AC-13075 a raised SUB-005 is not raised again and no resend follows a done resend`() {
        val list = listOf(v(1, SENT))
        VersionTransitions.decide(list, tick(at(60), s5 = true, resends = 1)) shouldBe Decision()
        VersionTransitions.decide(list, tick(at(60), resends = 2)).signals shouldBe listOf(Raise(RaisedCode.SUB_005, 1))
    }

    @Test
    fun `INV-AC-667 INV-AC-13075 SUB-010 is raised one second after dueAt and not at dueAt and never holds`() {
        val list = listOf(v(1, SENT_UNCONFIRMED))
        val atDue = tick(at(100), resends = 1, s5 = true)
        VersionTransitions.decide(list, atDue) shouldBe Decision()
        val after = VersionTransitions.decide(list, tick(at(100).plus(second), resends = 1, s5 = true))
        after shouldBe Decision(signals = listOf(Raise(RaisedCode.SUB_010, 1)))
        val raised = tick(at(100).plus(second), resends = 1, s5 = true, s10 = true)
        VersionTransitions.decide(list, raised) shouldBe Decision()
        RaisedCode.SUB_010.code shouldBe "SUB-010"
    }

    @Test
    fun `INV-AC-13075 a tick never produces a transition other than SENT to SENT_UNCONFIRMED`() {
        for (s in LifecycleState.entries) {
            val d = VersionTransitions.decide(listOf(v(1, s, "ACK-1")), tick(at(1000)))
            d.transitions.all { it == Transition(1, SENT, SENT_UNCONFIRMED) } shouldBe true
            if (s != SENT && s != SENT_UNCONFIRMED) d shouldBe Decision()
        }
    }

    // ---- anomalies and the golden table

    @Test
    fun `INV-AC-13076 an unknown version is an anomaly with no transition`() {
        val events =
            listOf(
                ApAccepted(9),
                AcknowledgementReceived(9, "A"),
                Rejected(9, RejectionSource.AP, reasons),
                tick(at(1), version = 9),
            )
        for (e in events) {
            VersionTransitions.decide(listOf(v(1, SENT)), e) shouldBe
                Decision(signals = listOf(Anomaly(AnomalyReason.UNKNOWN_VERSION)))
        }
    }

    @Test
    fun `INV-AC-13076 an acknowledged version keeps its id and another id is an anomaly`() {
        val list = listOf(v(1, ACKNOWLEDGED, "ACK-1"))
        VersionTransitions.decide(list, AcknowledgementReceived(1, "ACK-1")) shouldBe Decision()
        VersionTransitions.decide(list, AcknowledgementReceived(1, "ACK-2")) shouldBe
            Decision(signals = listOf(Anomaly(AnomalyReason.ACK_ID_DIFFERS)))
    }

    private enum class Cell { TRANSITION, UNCHANGED, ANOMALY }

    private fun cell(d: Decision): Cell =
        when {
            d.transitions.isNotEmpty() -> Cell.TRANSITION
            d.signals.any { it is Anomaly } -> Cell.ANOMALY
            else -> Cell.UNCHANGED
        }

    @Test
    fun `INV-AC-1851 INV-AC-13076 golden table of 54 cells`() {
        val ackOk = setOf("SENT", "SENT_UNCONFIRMED", "NETWORK_ISSUED", "QUEUED", "RETRY", "DEAD_LETTERED")
        val rejOk = setOf("QUEUED", "SENT", "SENT_UNCONFIRMED", "RETRY")
        var cells = 0
        for (s in LifecycleState.entries) {
            val list = listOf(v(1, s, "ACK-1"))
            val ap =
                when (s.name) {
                    "QUEUED", "RETRY" -> Cell.TRANSITION
                    "SENT" -> Cell.UNCHANGED
                    else -> Cell.ANOMALY
                }
            val ack =
                when {
                    s.name in ackOk -> Cell.TRANSITION
                    s == ACKNOWLEDGED -> Cell.UNCHANGED
                    else -> Cell.ANOMALY
                }
            val rej = if (s.name in rejOk) Cell.TRANSITION else Cell.ANOMALY
            cell(VersionTransitions.decide(list, ApAccepted(1))) shouldBe ap
            cell(VersionTransitions.decide(list, AcknowledgementReceived(1, "ACK-1"))) shouldBe ack
            cell(VersionTransitions.decide(list, Rejected(1, RejectionSource.IRAS, reasons))) shouldBe rej
            cells += 3
        }
        cells shouldBe 54
    }

    @Test
    fun `INV-AC-13073 LifecycleState equals the union of the schema enums`() {
        val text = File(System.getProperty("gst.contract.dir"), "schemas/lifecycle-states.schema.json").readText()

        fun enumOf(def: String): List<String> {
            val block = Regex("\"$def\"\\s*:\\s*\\{[^\\[]*\"enum\"\\s*:\\s*\\[([^\\]]*)]").find(text)!!.groupValues[1]
            return Regex("\"([A-Z_]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        }
        val union = enumOf("documentLevelState") + enumOf("versionLevelState")
        union.size shouldBe 18
        LifecycleState.entries.map { it.name } shouldContainExactlyInAnyOrder union
    }
}
