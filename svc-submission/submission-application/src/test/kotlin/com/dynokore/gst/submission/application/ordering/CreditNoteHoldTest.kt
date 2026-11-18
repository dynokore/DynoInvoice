package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.CodeSignals
import com.dynokore.gst.submission.application.arrival.RaisedCode
import com.dynokore.gst.submission.application.arrival.RaisedCodes
import com.dynokore.gst.submission.application.arrival.ScopeEntry
import com.dynokore.gst.submission.application.arrival.ScopeFacts
import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.queue.AcknowledgementBasis
import com.dynokore.gst.submission.application.queue.AcknowledgementInputs
import com.dynokore.gst.submission.application.queue.CatalogueEntry
import com.dynokore.gst.submission.application.queue.DueMarginCap
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.CreditNoteOption
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** The arrival hold of a credit note over fakes (INV-AC-24521). */
class CreditNoteHoldTest {
    private val t1 = TenantId(UUID.randomUUID())
    private val zone = ZoneId.of("Asia/Singapore")
    private val calls = mutableListOf<String>()
    private val entries = mutableMapOf<String, ScopeEntry>()
    private var precedingStates: List<LifecycleState>? = listOf(LifecycleState.QUEUED)
    private var option: () -> CreditNoteOption = { CreditNoteOption.RELEASE_ALONE }
    private var optionDate: LocalDate? = null
    private var basis: () -> AcknowledgementBasis = {
        AcknowledgementBasis(LocalDate.parse("2026-03-20"), SafetyMarginAnswer.Known(PackMargin.of(2, "DAYS")!!))
    }
    private var holdResult = 1
    private val holds = mutableListOf<Triple<TenantId, String, Int>>()
    private val appended = mutableListOf<RaisedCode>()
    private var now = Instant.parse("2026-03-10T00:00:00Z")

    private val facts =
        object : ScopeFacts {
            override fun of(
                tenant: TenantId,
                docUuid: String,
            ): ScopeEntry? {
                calls += "facts"
                return entries[docUuid]
            }
        }
    private val options =
        CreditNoteOptions { _, _, on ->
            calls += "options"
            optionDate = on
            option()
        }
    private val states =
        PrecedingStates { _, _ ->
            calls += "states"
            precedingStates
        }
    private val store =
        object : HoldStore {
            override fun holdReady(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): Int {
                calls += "hold"
                holds += Triple(tenant, docUuid, version)
                return holdResult
            }

            override fun releaseHeld(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): Int = error("not used")

            override fun heldBy(
                tenant: TenantId,
                precedingDocUuid: String,
            ): List<HeldVersion> = error("not used")

            override fun held(
                tenant: TenantId,
                limit: Int,
            ): List<HeldVersion> = error("not used")
        }
    private val codes =
        object : RaisedCodes {
            override fun append(
                tenant: TenantId,
                raised: RaisedCode,
                raisedAt: Instant,
            ): Boolean {
                calls += "raise"
                appended += raised
                return true
            }
        }
    private val signals =
        object : CodeSignals {
            override fun stage(raised: RaisedCode) = Unit
        }
    private val catalogue =
        ErrorCatalogue {
            if (it == "SUB-011" || it == "SUB-012") CatalogueEntry(it, "Tax operations", "MANUAL") else null
        }
    private val raising =
        CodeRaising(catalogue, codes, signals, Clock.fixed(Instant.parse("2026-03-10T00:00:00Z"), ZoneOffset.UTC))

    private fun sut(): CreditNoteHold {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val releaseAt = ReleaseAloneAt(AcknowledgementInputs { _, _ -> basis() }, zone)
        return CreditNoteHold(facts, options, states, releaseAt, store, raising, clock, zone)
    }

    private fun creditNote() {
        entries["CN1"] = ScopeEntry("E-001", "2026-Q1", RowKind.CREDIT_NOTE, null, "P1")
    }

    private fun arrive(firstVersion: Boolean = true) = sut().onArrival(t1, "CN1", 1, firstVersion)

    private val held = HoldOutcome.Held
    private val proceed = HoldOutcome.Proceed(null)

    private fun codesRaised() = appended.map { listOf(it.code, it.docUuid, it.version, it.entityRef, it.periodRef) }

    @Test
    fun `INV-AC-24521 H1 a pending original holds the credit note with SUB-011`() {
        creditNote()
        arrive() shouldBe held
        holds shouldBe listOf(Triple(t1, "CN1", 1))
        codesRaised() shouldBe listOf(listOf("SUB-011", "CN1", 1, "E-001", "2026-Q1"))
        calls.filter { it == "hold" || it == "raise" } shouldBe listOf("hold", "raise")
    }

    @Test
    fun `INV-AC-24521 H2 an ACKNOWLEDGED original releases with nothing written or raised`() {
        creditNote()
        precedingStates = listOf(LifecycleState.ACKNOWLEDGED)
        arrive() shouldBe proceed
        holds shouldBe emptyList()
        appended shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 H3 a CANCELLED original releases with nothing written`() {
        creditNote()
        precedingStates = listOf(LifecycleState.CANCELLED)
        arrive() shouldBe proceed
        holds shouldBe emptyList()
        appended shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 H4 an unknown original counts as pending and is held with SUB-011`() {
        creditNote()
        precedingStates = null
        arrive() shouldBe held
        codesRaised().map { it[0] } shouldBe listOf("SUB-011")
    }

    @Test
    fun `INV-AC-24521 H5 past the margin with RELEASE_ALONE proceeds in DEADLINE_NEAR with SUB-012`() {
        creditNote()
        now = Instant.parse("2026-03-19T00:00:00Z")
        arrive() shouldBe HoldOutcome.Proceed(Lane.DEADLINE_NEAR)
        holds shouldBe emptyList()
        codesRaised().map { it[0] } shouldBe listOf("SUB-012")
    }

    @Test
    fun `INV-AC-24521 H6 past the margin with HOLD_FOR_APPROVER is held with SUB-012 and no SUB-011`() {
        creditNote()
        now = Instant.parse("2026-03-19T00:00:00Z")
        option = { CreditNoteOption.HOLD_FOR_APPROVER }
        arrive() shouldBe held
        holds.size shouldBe 1
        codesRaised().map { it[0] } shouldBe listOf("SUB-012")
    }

    @Test
    fun `INV-AC-24521 H7 a later version proceeds and no port is read`() {
        creditNote()
        arrive(firstVersion = false) shouldBe proceed
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 H8 no entry, another kind or no preceding link proceeds with nothing written`() {
        arrive() shouldBe proceed
        entries["CN1"] = ScopeEntry("E-001", "2026-Q1", RowKind.INVOICE, null, "P1")
        arrive() shouldBe proceed
        entries["CN1"] = ScopeEntry("E-001", "2026-Q1", RowKind.CREDIT_NOTE, null, null)
        arrive() shouldBe proceed
        holds shouldBe emptyList()
        appended shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 H9 an unknown deadline proceeds with nothing written or raised`() {
        creditNote()
        val known = SafetyMarginAnswer.Known(PackMargin.of(2, "DAYS")!!)
        basis = { AcknowledgementBasis(null, known) }
        arrive() shouldBe proceed
        basis = { AcknowledgementBasis(LocalDate.parse("2026-03-20"), SafetyMarginAnswer.Unavailable) }
        arrive() shouldBe proceed
        basis = { throw IllegalStateException("unreadable") }
        arrive() shouldBe proceed
        holds shouldBe emptyList()
        appended shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 H10 holdReady returning 0 throws naming the Doc UUID and version and raises nothing`() {
        creditNote()
        holdResult = 0
        val e = shouldThrow<IllegalStateException> { arrive() }
        e.message!! shouldContain "CN1"
        e.message!! shouldContain "version 1"
        appended shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 H11 an option that throws propagates and nothing is written`() {
        creditNote()
        option = { throw IllegalStateException("no election") }
        shouldThrow<IllegalStateException> { arrive() }
        holds shouldBe emptyList()
        appended shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 H12 the option is read at the clock date in the business zone`() {
        creditNote()
        now = Instant.parse("2026-03-10T17:00:00Z")
        arrive()
        optionDate shouldBe LocalDate.parse("2026-03-11")
    }

    @Test
    fun `INV-AC-24521 H13 the cap arithmetic is the due date plus a day in the zone minus the margin`() {
        val due = LocalDate.parse("2026-03-20")
        DueMarginCap.of(due, PackMargin.of(2, "DAYS")!!, zone) shouldBe Instant.parse("2026-03-18T16:00:00Z")
        DueMarginCap.of(due, PackMargin.of(6, "HOURS")!!, zone) shouldBe Instant.parse("2026-03-20T10:00:00Z")
    }
}
