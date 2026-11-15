package com.dynokore.gst.access.application.review

import com.dynokore.gst.access.application.separation.SoleOperatorAck
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.review.Cadence
import com.dynokore.gst.access.domain.review.CampaignStatus
import com.dynokore.gst.access.domain.review.PeriodLabel
import com.dynokore.gst.access.domain.review.ReviewCampaign
import com.dynokore.gst.access.domain.review.ReviewDecision
import com.dynokore.gst.access.domain.review.ReviewOutcome
import com.dynokore.gst.access.domain.review.ReviewedGrant
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReviewTest {
    private val now = Instant.parse("2026-11-16T08:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                val storedBefore = stored.toList()
                val outcomesBefore = outcomeRows.toList()
                val auditsBefore = audits.toList()
                try {
                    return block()
                } catch (e: IllegalStateException) {
                    stored.clear()
                    stored.addAll(storedBefore)
                    outcomeRows.clear()
                    outcomeRows.addAll(outcomesBefore)
                    audits.clear()
                    audits.addAll(auditsBefore)
                    throw e
                }
            }
        }
    private val alice = IdentityRef("alice")
    private val bob = IdentityRef("bob")
    private val tenantGrants =
        listOf(
            ReviewedGrant(GrantRef("g1"), alice),
            ReviewedGrant(GrantRef("g2"), bob),
            ReviewedGrant(GrantRef("g3"), bob),
        )
    private val stored = mutableListOf<ReviewCampaign>()
    private val outcomeRows = mutableListOf<ReviewOutcome>()
    private val audits = mutableListOf<ReviewAuditEntry>()
    private var ack: SoleOperatorAck? = null
    private var vp05 = VpAvailability.PLANNED
    private var cadence = CadenceAnswer.QUARTERLY
    private var cadenceCalls = 0
    private val revoked = mutableListOf<Set<GrantRef>>()
    private var revokeFails = false
    private val foreignCampaign =
        ReviewCampaign("OTHER-TENANT", PeriodLabel.Quarter("2026Q3"), Cadence.QUARTERLY, emptyList(), bob, now)

    private val campaigns =
        object : ReviewCampaigns {
            override fun insert(campaign: ReviewCampaign) {
                stored += campaign
            }

            override fun find(ref: String) = stored.firstOrNull { it.ref == ref }

            // the fake store also holds another tenant's campaign, which the bound tenant never sees
            override fun findByPeriod(period: PeriodLabel) =
                (stored + foreignCampaign).firstOrNull { it.period == period && it !== foreignCampaign }

            override fun nextRef() = "C-${stored.size + 1}"
        }
    private val outcomes =
        object : ReviewOutcomes {
            override fun find(
                campaign: String,
                grant: GrantRef,
            ) = outcomeRows.firstOrNull { it.campaign == campaign && it.grant == grant }

            override fun insert(outcome: ReviewOutcome) {
                outcomeRows += outcome
            }

            override fun recorded(campaign: String) = outcomeRows.filter { it.campaign == campaign }
        }
    private val acks =
        object : SoleOperatorAcks {
            override fun findActive() = ack?.takeIf { it.active }

            override fun find(ref: String) = ack?.takeIf { it.ref == ref }

            override fun lockActive() = findActive()

            override fun holders(role: Role): Set<IdentityRef> = error("not used")

            override fun insert(compensatingControls: String): SoleOperatorAck = error("not used")

            override fun withdraw(ref: String) = error("not used")
        }
    private val cadences =
        ReviewCadenceSource {
            cadenceCalls++
            cadence
        }
    private val revocations =
        ReviewRevocations { _, grants, _ ->
            revoked += grants
            check(!revokeFails) { "revocation failed" }
        }
    private val run =
        RunAccessReviewUseCase(
            tx,
            ReviewSources({ vp05 }, cadences, { tenantGrants }),
            campaigns,
            { audits += it },
            clock,
        )
    private val close =
        CloseAccessReviewUseCase(tx, ReviewStores(campaigns, outcomes), acks, revocations, { audits += it }, clock)

    private fun rec(
        a: IdentityRef,
        c: String,
        g: GrantRef,
        d: ReviewDecision,
    ) = close.handle(a, c, g, d)

    private fun open(): String = (run.handle(alice, "2026Q3") as RunAccessReviewOutcome.Opened).campaign.ref

    private fun ack(active: Boolean) = SoleOperatorAck("ACK-1", "T-1", "controls", active)

    @Test
    fun `INV-AC-23350 a quarter label opens a quarterly campaign holding every grant of the tenant`() {
        val opened = assertInstanceOf(RunAccessReviewOutcome.Opened::class.java, run.handle(alice, "2026Q3"))
        assertEquals(tenantGrants, opened.campaign.grants)
        assertEquals(Cadence.QUARTERLY, opened.campaign.cadence)
        assertEquals(listOf(opened.campaign), stored)
    }

    @Test
    fun `INV-AC-23350 a campaign missing one grant would differ from the tenant grants`() {
        val opened = (run.handle(alice, "2026Q3") as RunAccessReviewOutcome.Opened).campaign
        assertTrue(opened.grants.dropLast(1) != tenantGrants)
    }

    @Test
    fun `INV-AC-23350 the monthly label is refused while VP-05 is planned and the others are invalid`() {
        assertEquals(RunAccessReviewOutcome.MonthlyUnavailable, run.handle(alice, "2026-09"))
        listOf("2026H1", "2026", "2026q3", "2026Q5", "2026Q31", "2026Q3 ").forEach {
            assertEquals(RunAccessReviewOutcome.InvalidPeriod, run.handle(alice, it), it)
        }
        assertTrue(stored.isEmpty())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `INV-AC-23350 planned never consults the cadence source`() {
        run.handle(alice, "2026Q3")
        run.handle(alice, "2026-09")
        assertEquals(0, cadenceCalls)
    }

    @Test
    fun `INV-AC-23350 stored quarterly opens a quarter label and refuses a month label as a mismatch`() {
        vp05 = VpAvailability.AVAILABLE
        assertEquals(RunAccessReviewOutcome.CadenceMismatch, run.handle(alice, "2026-09"))
        assertTrue(stored.isEmpty())
        val opened = assertInstanceOf(RunAccessReviewOutcome.Opened::class.java, run.handle(alice, "2026Q3"))
        assertEquals(Cadence.QUARTERLY, opened.campaign.cadence)
    }

    @Test
    fun `INV-AC-23350 stored monthly opens a month label with cadence monthly and refuses a quarter label`() {
        vp05 = VpAvailability.AVAILABLE
        cadence = CadenceAnswer.MONTHLY
        assertEquals(RunAccessReviewOutcome.CadenceMismatch, run.handle(alice, "2026Q3"))
        assertTrue(stored.isEmpty())
        assertTrue(audits.isEmpty())
        val opened = assertInstanceOf(RunAccessReviewOutcome.Opened::class.java, run.handle(alice, "2026-09"))
        assertEquals(Cadence.MONTHLY, opened.campaign.cadence)
        assertEquals(RunAccessReviewOutcome.InvalidPeriod, run.handle(alice, "2026-13"))
    }

    @Test
    fun `INV-AC-23350 an unreadable cadence refuses both labels and writes nothing`() {
        vp05 = VpAvailability.AVAILABLE
        cadence = CadenceAnswer.UNREADABLE
        assertEquals(RunAccessReviewOutcome.CadenceNotReadable, run.handle(alice, "2026Q3"))
        assertEquals(RunAccessReviewOutcome.CadenceNotReadable, run.handle(alice, "2026-09"))
        assertTrue(stored.isEmpty())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `INV-AC-23350 a repeated period answers the existing campaign, also after it is closed`() {
        val first = (run.handle(alice, "2026Q3") as RunAccessReviewOutcome.Opened).campaign
        val auditCount = audits.size
        assertEquals(RunAccessReviewOutcome.Existing(first), run.handle(bob, "2026Q3"))
        rec(bob, first.ref, GrantRef("g1"), ReviewDecision.KEEP)
        rec(alice, first.ref, GrantRef("g2"), ReviewDecision.KEEP)
        rec(alice, first.ref, GrantRef("g3"), ReviewDecision.KEEP)
        val closedAudits = audits.size
        assertEquals(RunAccessReviewOutcome.Existing(first), run.handle(alice, "2026Q3"))
        assertEquals(1, stored.size)
        assertEquals(closedAudits, audits.size)
        assertTrue(auditCount < closedAudits)
        val second = assertInstanceOf(RunAccessReviewOutcome.Opened::class.java, run.handle(alice, "2026Q4"))
        assertEquals(2, stored.size)
        assertEquals("C-2", second.campaign.ref)
    }

    @Test
    fun `INV-AC-23349 revocations are applied once at close with only the revoke grants`() {
        val c = open()
        rec(bob, c, GrantRef("g1"), ReviewDecision.REVOKE)
        rec(alice, c, GrantRef("g2"), ReviewDecision.KEEP)
        assertTrue(revoked.isEmpty())
        assertEquals(
            CloseAccessReviewOutcome.Recorded(CampaignStatus.CLOSED),
            rec(alice, c, GrantRef("g3"), ReviewDecision.REVOKE),
        )
        assertEquals(listOf(setOf(GrantRef("g1"), GrantRef("g3"))), revoked)
        // a repeated final outcome calls nothing again
        assertEquals(
            CloseAccessReviewOutcome.Repeated(CampaignStatus.CLOSED),
            rec(alice, c, GrantRef("g3"), ReviewDecision.REVOKE),
        )
        assertEquals(1, revoked.size)
    }

    @Test
    fun `INV-AC-23349 a campaign with only keep decisions does not call the revocation port`() {
        val c = open()
        rec(bob, c, GrantRef("g1"), ReviewDecision.KEEP)
        rec(alice, c, GrantRef("g2"), ReviewDecision.KEEP)
        rec(alice, c, GrantRef("g3"), ReviewDecision.KEEP)
        assertTrue(revoked.isEmpty())
        assertEquals(4, audits.size) // one run entry and one per outcome; no extra entry for revocations
    }

    @Test
    fun `INV-AC-23349 a failing revocation port rolls the closing outcome back`() {
        val c = open()
        rec(bob, c, GrantRef("g1"), ReviewDecision.REVOKE)
        rec(alice, c, GrantRef("g2"), ReviewDecision.KEEP)
        revokeFails = true
        assertThrows(IllegalStateException::class.java) { rec(alice, c, GrantRef("g3"), ReviewDecision.KEEP) }
        assertEquals(2, outcomeRows.size)
        assertNull(outcomes.find(c, GrantRef("g3")))
        revokeFails = false
        assertEquals(
            CloseAccessReviewOutcome.Recorded(CampaignStatus.CLOSED),
            rec(alice, c, GrantRef("g3"), ReviewDecision.KEEP),
        )
    }

    @Test
    fun `INV-AC-23349 an outcome on another grant is stored with user and time, the last one closes the campaign`() {
        val c = open()
        assertEquals(
            CloseAccessReviewOutcome.Recorded(CampaignStatus.OPEN),
            rec(bob, c, GrantRef("g1"), ReviewDecision.KEEP),
        )
        assertEquals(ReviewOutcome(c, GrantRef("g1"), ReviewDecision.KEEP, bob, now), outcomeRows.single())
        // alice reviews bob's grants (a second holder); the last missing outcome closes the campaign
        assertEquals(
            CloseAccessReviewOutcome.Recorded(CampaignStatus.OPEN),
            rec(alice, c, GrantRef("g2"), ReviewDecision.REVOKE),
        )
        assertEquals(
            CloseAccessReviewOutcome.Recorded(CampaignStatus.CLOSED),
            rec(alice, c, GrantRef("g3"), ReviewDecision.KEEP),
        )
    }

    @Test
    fun `INV-AC-23349 the same outcome again changes nothing and a different one is a conflict`() {
        val c = open()
        rec(bob, c, GrantRef("g1"), ReviewDecision.KEEP)
        val auditCount = audits.size
        assertEquals(
            CloseAccessReviewOutcome.Repeated(CampaignStatus.OPEN),
            rec(bob, c, GrantRef("g1"), ReviewDecision.KEEP),
        )
        assertEquals(1, outcomeRows.size)
        assertEquals(auditCount, audits.size)
        assertEquals(CloseAccessReviewOutcome.Conflict, rec(bob, c, GrantRef("g1"), ReviewDecision.REVOKE))
        assertEquals(ReviewDecision.KEEP, outcomeRows.single().decision)
    }

    @Test
    fun `INV-AC-23349 an own grant is refused without an ACTIVE acknowledgement and with a withdrawn one`() {
        val c = open()
        assertEquals(CloseAccessReviewOutcome.OwnGrantRefused, rec(alice, c, GrantRef("g1"), ReviewDecision.KEEP))
        ack = ack(active = false)
        assertEquals(CloseAccessReviewOutcome.OwnGrantRefused, rec(alice, c, GrantRef("g1"), ReviewDecision.KEEP))
        assertTrue(outcomeRows.isEmpty())
    }

    @Test
    fun `INV-AC-23349 an own grant with an ACTIVE acknowledgement is recorded and the audit names it`() {
        val c = open()
        ack = ack(active = true)
        assertEquals(
            CloseAccessReviewOutcome.Recorded(CampaignStatus.OPEN),
            rec(alice, c, GrantRef("g1"), ReviewDecision.KEEP),
        )
        assertEquals("ACK-1", outcomeRows.single().ackRef)
        assertEquals("ACK-1", audits.last().ackRef)
        assertEquals("alice", audits.last().actor)
        rec(alice, c, GrantRef("g2"), ReviewDecision.KEEP)
        assertNull(audits.last().ackRef)
    }

    @Test
    fun `INV-AC-23349 an unknown campaign or a grant outside it is refused`() {
        val c = open()
        assertEquals(CloseAccessReviewOutcome.CampaignNotFound, rec(bob, "nope", GrantRef("g1"), ReviewDecision.KEEP))
        assertEquals(CloseAccessReviewOutcome.GrantNotInCampaign, rec(bob, c, GrantRef("g9"), ReviewDecision.KEEP))
    }
}
