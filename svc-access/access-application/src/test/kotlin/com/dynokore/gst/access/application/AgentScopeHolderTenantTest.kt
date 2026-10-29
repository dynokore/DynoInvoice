package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.consent.AgentScope
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Permission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * INV-Q-9320 A: a consent-derived grant holder is permitted only when the holder's agent tenant equals the consent's
 * `agentRef`, by case-sensitive string equality, on top of the live consent and the scope item.
 */
class AgentScopeHolderTenantTest {
    private val client = EntityRef("CLI-A")
    private val today = LocalDate.of(2026, 10, 27)
    private val approve = Permission("mapping-pack.approve")

    private class FakeConsents(
        private val live: Consent?,
    ) : ConsentRepository {
        override fun find(ref: ConsentRef): Consent? = live

        override fun lock(ref: ConsentRef): Consent? = live

        override fun findLiveForClient(client: EntityRef): Consent? = live

        override fun insert(consent: Consent) = error("not used")

        override fun update(
            before: Consent,
            after: Consent,
        ) = error("not used")

        override fun nextAuthorityVersion(): Long = error("not used")

        override fun nextRef(): ConsentRef = error("not used")
    }

    private fun consent(
        agent: String,
        forClient: EntityRef = client,
        items: Set<ScopeItem> = setOf(ScopeItem.MAPPING_APPROVAL),
        status: ConsentStatus = ConsentStatus.LIVE,
    ) = Consent(
        ref = ConsentRef("CON-1"),
        client = forClient,
        agent = IdentityRef(agent),
        terms = ConsentTerms(items, LocalDate.of(2026, 1, 1), "director@client.example"),
        status = status,
        handoverStatementRef = if (status == ConsentStatus.WITHDRAWN) "HAND-1" else null,
        version = 0,
    )

    private fun scope(live: Consent?) =
        AgentScope(
            FakeConsents(live),
            ScopeItemRequirements(mapOf(approve to ScopeItem.MAPPING_APPROVAL)),
        )

    private fun permits(
        holder: String,
        live: Consent?,
        permission: Permission = approve,
    ) = scope(live).permitsClient(TenantRef(holder), client, permission, today)

    @Test
    fun `INV-Q-9320 equal tenant, live consent and item held is permitted`() {
        assertEquals(true, permits("tenant-1", consent("tenant-1")))
    }

    @Test
    fun `INV-Q-9320 tenant T-1 against agent T-2 is refused`() {
        assertEquals(false, permits("T-1", consent("T-2")))
    }

    @Test
    fun `INV-Q-9320 tenant Tenant-1 against agent tenant-1 is refused`() {
        assertEquals(false, permits("Tenant-1", consent("tenant-1")))
    }

    @Test
    fun `INV-Q-9320 tenant with a trailing space against agent tenant-1 is refused`() {
        assertEquals(false, permits("tenant-1 ", consent("tenant-1")))
    }

    @Test
    fun `INV-Q-9320 no consent is refused`() {
        assertEquals(false, permits("tenant-1", null))
    }

    @Test
    fun `INV-Q-9320 consent of another client entity is refused`() {
        assertEquals(false, permits("tenant-1", consent("tenant-1", forClient = EntityRef("CLI-B"))))
    }

    @Test
    fun `INV-Q-9320 withdrawn consent is refused`() {
        assertEquals(false, permits("tenant-1", consent("tenant-1", status = ConsentStatus.WITHDRAWN)))
    }

    @Test
    fun `INV-Q-9320 consent without the item is refused`() {
        assertEquals(false, permits("tenant-1", consent("tenant-1", items = setOf(ScopeItem.FILE_GST_RETURN))))
    }

    @Test
    fun `INV-Q-9320 permission outside the table is refused`() {
        assertEquals(false, permits("tenant-1", consent("tenant-1"), Permission("audit-pack.export")))
    }
}
