package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.derivation.ReleaseRefusal
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ReleaseHeldDocumentTest {
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000e$n")

    private val log = mutableListOf<String>()
    private val now = Instant.parse("2026-11-18T01:02:03Z")
    private val d1 = id(1)
    private val d2 = id(2)
    private val d3 = id(3)
    private val d4 = id(4)
    private val unknown = id(9)

    private fun doc(
        u: UUID,
        state: String,
        reasons: List<String>,
    ) = HoldableDocument(u, "E1", Direction.SALE, LocalDate.parse("2026-03-20"), "2026-Q1", state, reasons)

    private val docs =
        listOf(
            doc(d1, "HELD", listOf("ING-024")),
            doc(d2, "HELD", listOf("ING-017", "MDM-001")),
            doc(d3, "HELD", listOf("MDM-001")),
            doc(d4, "INGESTED", emptyList()),
        ).associateBy { it.docUuid }.toMutableMap()

    private val store =
        object : DocumentReleaseStore {
            override fun lockForRelease(docUuid: UUID): HoldableDocument? {
                log += "lock"
                return docs[docUuid]
            }

            override fun markReleased(docUuid: UUID) {
                log += "mark"
                docs[docUuid] = docs.getValue(docUuid).copy(state = DocumentStates.INGESTED, holdReasons = emptyList())
            }
        }

    private val recorded = mutableListOf<RecordedRelease>()
    private val causes = mutableMapOf<Pair<UUID, String>, String>()

    private val records =
        object : HeldReleaseRecords {
            override fun find(
                docUuid: UUID,
                holdCode: String,
            ): RecordedRelease? {
                log += "find"
                return recorded.firstOrNull { it.docUuid == docUuid && it.holdCode == holdCode }
            }

            override fun record(release: RecordedRelease) {
                log += "record"
                recorded += release
            }

            override fun holdCause(
                docUuid: UUID,
                holdCode: String,
            ): String? {
                log += "cause"
                return causes[docUuid to holdCode]
            }

            override fun removeReason(
                docUuid: UUID,
                holdCode: String,
            ) {
                log += "remove"
                docs[docUuid] = docs.getValue(docUuid).let { it.copy(holdReasons = it.holdReasons - holdCode) }
            }
        }

    private val authority =
        ReleaseAuthority { user, entity ->
            log += "permit"
            user == "U1" && entity == "E1"
        }

    private val events = mutableListOf<ReleasedEvent>()
    private val publisher =
        ReleasedEventPublisher {
            log += "stage"
            events += it
        }
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
    private val subject =
        ReleaseHeldDocument(store, records, authority, publisher, tx, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `INV-AC-24272 permitted release of D1 records once, empties reasons, returns INGESTED and stages one fact`() {
        subject.release(d1, "ING-024", "U1") shouldBe ReleaseHeldResult.Released
        recorded shouldBe listOf(RecordedRelease(d1, "ING-024", "U1", now))
        docs.getValue(d1).state shouldBe DocumentStates.INGESTED
        events shouldBe listOf(ReleasedEvent(d1, listOf("ING-024"), "E1"))
        log shouldBe listOf("lock", "permit", "find", "cause", "record", "remove", "mark", "stage")
    }

    @Test
    fun `INV-AC-24272 the repeat returns the recorded release unchanged and stages nothing`() {
        subject.release(d1, "ING-024", "U1")
        log.clear()
        val first = recorded.single()
        subject.release(d1, "ING-024", "U1") shouldBe ReleaseHeldResult.AlreadyReleased(first)
        recorded.size shouldBe 1
        events.size shouldBe 1
        log shouldBe listOf("lock", "permit", "find")
    }

    @Test
    fun `INV-AC-24272 not-permitted U2 gets REFUSED NOT_PERMITTED with no write for held D1 and INGESTED D4`() {
        subject.release(d1, "ING-024", "U2") shouldBe ReleaseHeldResult.Refused(ReleaseRefusal.NOT_PERMITTED)
        subject.release(d4, "ING-024", "U2") shouldBe ReleaseHeldResult.Refused(ReleaseRefusal.NOT_PERMITTED)
        log shouldBe listOf("lock", "permit", "lock", "permit")
        recorded.size shouldBe 0
        events.size shouldBe 0
        docs.getValue(d1).holdReasons shouldBe listOf("ING-024")
    }

    @Test
    fun `INV-AC-24272 D2 with ING-017 records, removes the code, stays HELD with MDM-001 and stages no fact`() {
        subject.release(d2, "ING-017", "U1") shouldBe ReleaseHeldResult.Released
        recorded shouldBe listOf(RecordedRelease(d2, "ING-017", "U1", now))
        docs.getValue(d2).state shouldBe DocumentStates.HELD
        docs.getValue(d2).holdReasons shouldBe listOf("MDM-001")
        events.size shouldBe 0
        log.contains("mark") shouldBe false
    }

    @Test
    fun `INV-AC-24272 D3, INGESTED D4 and unknown document give NOT_HELD_WITH_CODE, no write`() {
        subject.release(d3, "ING-017", "U1") shouldBe ReleaseHeldResult.NotHeldWithCode
        subject.release(d4, "ING-024", "U1") shouldBe ReleaseHeldResult.NotHeldWithCode
        log.clear()
        subject.release(unknown, "ING-024", "U1") shouldBe ReleaseHeldResult.NotHeldWithCode
        log shouldBe listOf("lock")
        recorded.size shouldBe 0
        events.size shouldBe 0
        docs.getValue(d3).holdReasons shouldBe listOf("MDM-001")
    }

    @Test
    fun `INV-AC-24272 a cause user equal to the releasing user gets REFUSED SAME_USER with no write`() {
        causes[d1 to "ING-024"] = "U1"
        subject.release(d1, "ING-024", "U1") shouldBe ReleaseHeldResult.Refused(ReleaseRefusal.SAME_USER)
        log shouldBe listOf("lock", "permit", "find", "cause")
        recorded.size shouldBe 0
        events.size shouldBe 0
        docs.getValue(d1).state shouldBe DocumentStates.HELD
    }

    @Test
    fun `INV-AC-24272 a code that is not a manual code is refused and a blank user throws`() {
        subject.release(d3, "MDM-001", "U1") shouldBe ReleaseHeldResult.Refused(ReleaseRefusal.NOT_MANUAL_HOLD)
        recorded.size shouldBe 0
        assertThrows<IllegalArgumentException> { subject.release(d1, "ING-024", " ") }
    }
}
