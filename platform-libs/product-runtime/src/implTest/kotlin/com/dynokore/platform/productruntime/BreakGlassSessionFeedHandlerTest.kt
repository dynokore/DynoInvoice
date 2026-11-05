package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.NOW
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.feed.BreakGlassSessionFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.BreakGlassStatus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** INV-T-9311: BreakGlassSessionChanged feeds the break-glass session replica (INV-Q-9119 A). */
class BreakGlassSessionFeedHandlerTest {
    private val replica = AuthorityReplica(CLOCK)
    private val handler = BreakGlassSessionFeedHandler(replica)
    private val type = PlatformEventCatalogue.BREAK_GLASS_SESSION_CHANGED.eventType

    private fun payload(
        status: String,
        approvedBy: String? = """"approvedBy":["P1","P2"],""",
    ) = "{$approvedBy" +
        """"unlockRef":"U-1","tenantRef":"${TENANT.value}","status":"$status","expiresAt":"2026-10-12T06:00:00Z"}"""

    @Test
    fun `an OPEN event restricts its approver and the SEALED event for the same unlockRef removes the restriction`() {
        handler.handle(envelope(type, payload("OPEN"))) shouldBe HandleOutcome.APPLIED
        val session = replica.breakGlassSessions.restrictedSession(TENANT, "P1", NOW).shouldNotBeNull()
        session.unlockRef shouldBe "U-1"
        session.approvedBy shouldBe listOf("P1", "P2")
        session.expiresAt.toString() shouldBe "2026-10-12T06:00:00Z"
        replica.breakGlassSessions.restrictedSession(TENANT, "P3", NOW).shouldBeNull()

        handler.handle(envelope(type, payload("SEALED")))
        replica.breakGlassSessions.restrictedSession(TENANT, "P1", NOW).shouldBeNull()
        replica.breakGlassSessions.held(TENANT, "U-1")?.status shouldBe BreakGlassStatus.SEALED
    }

    @Test
    fun `an unknown status text is passed on and the replica ignores it`() {
        handler.handle(envelope(type, payload("OPEN")))
        handler.handle(envelope(type, payload("PAUSED"))) shouldBe HandleOutcome.APPLIED
        replica.breakGlassSessions.held(TENANT, "U-1")?.status shouldBe BreakGlassStatus.OPEN
    }

    @Test
    fun `an event of another type, a look-alike type or a payload without approvedBy is a ContractViolation`() {
        listOf(
            "com.dynokore.gst.platform.PolicyChanged.v1",
            "com.dynokore.gst.platform.BreakGlassSessionChange.v1",
            "com.dynokore.gst.platform.BreakGlassSessionChanged.v2",
        ).forEach { wrong ->
            assertThrows<ContractViolation> { handler.handle(envelope(wrong, payload("OPEN"))) }
        }
        assertThrows<ContractViolation> { handler.handle(envelope(type, payload("OPEN", approvedBy = ""))) }
        replica.breakGlassSessions.held(TENANT, "U-1").shouldBeNull()
    }
}
