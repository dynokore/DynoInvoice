package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.authorise.OperatorOverlay
import com.dynokore.gst.access.application.authorise.PlatformRead
import com.dynokore.gst.access.application.consent.AgentScope
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.elevation.ElevationAsk
import com.dynokore.gst.access.domain.elevation.ElevationRecord
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * INV-AC-17814: an agent.operator grant is permitted only when the grant's agent tenant equals the consent's
 * `agentRef`, by case-sensitive string equality, on top of the live consent and the scope item.
 */
class AgentScopeOperatorTenantTest {
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
    ) = scope(live).permits(TenantRef(holder), client, permission, today)

    @Test
    fun `INV-AC-17814 equal tenant, live consent and item held is permitted`() {
        assertEquals(true, permits("tenant-1", consent("tenant-1")))
    }

    @Test
    fun `INV-AC-17814 tenant T-1 against agent T-2 is refused`() {
        assertEquals(false, permits("T-1", consent("T-2")))
    }

    @Test
    fun `INV-AC-17814 tenant Tenant-1 against agent tenant-1 is refused`() {
        assertEquals(false, permits("Tenant-1", consent("tenant-1")))
    }

    @Test
    fun `INV-AC-17814 tenant with a trailing space is refused`() {
        assertEquals(false, permits("tenant-1 ", consent("tenant-1")))
    }

    @Test
    fun `INV-AC-17814 no consent is refused`() {
        assertEquals(false, permits("tenant-1", null))
    }

    @Test
    fun `INV-AC-17814 a consent of another client is refused`() {
        assertEquals(false, permits("tenant-1", consent("tenant-1", forClient = EntityRef("CLI-B"))))
    }

    @Test
    fun `INV-AC-17814 a withdrawn consent is refused`() {
        assertEquals(false, permits("tenant-1", consent("tenant-1", status = ConsentStatus.WITHDRAWN)))
    }

    @Test
    fun `INV-AC-17814 a consent without the item is refused`() {
        assertEquals(false, permits("tenant-1", consent("tenant-1", items = emptySet())))
    }

    @Test
    fun `INV-AC-17814 a permission outside the table is refused`() {
        assertEquals(false, permits("tenant-1", consent("tenant-1"), Permission("audit-pack.export")))
    }

    // ---- through Authorise.handle: the guard that the grant's tenant equals the entity's membership tenant ----

    private val ingest = Permission("transaction.ingest")
    private val operator = IdentityRef("user:operator")
    private val moment = Instant.parse("2026-10-27T00:00:00Z")
    private val entityTenant = TenantRef("T-1")

    private fun operatorGrant(tenant: String) =
        Grant.issue(
            GrantHeader(GrantRef("G-$tenant"), TenantRef(tenant), operator),
            Role.AGENT_OPERATOR,
            Scope.Entity(client),
            ValidityWindow(moment.minusSeconds(3600), null),
        )

    private fun decide(
        grantTenant: String,
        live: Consent?,
    ): Decision {
        val grants =
            object : GrantRepository {
                override fun findByIdentity(identity: IdentityRef): List<Grant> = listOf(operatorGrant(grantTenant))

                override fun find(ref: GrantRef): Grant? = error("not used")

                override fun findByRepeatKey(
                    identity: IdentityRef,
                    roleWire: String,
                    scope: Scope,
                ): Grant? = error("not used")

                override fun insert(grant: Grant) = error("not used")

                override fun update(
                    before: Grant,
                    after: Grant,
                ) = error("not used")

                override fun lockIdentity(
                    identity: IdentityRef,
                    kind: GranteeKind,
                ) = error("not used")

                override fun nextAuthorityVersion(): Long = error("not used")

                override fun nextGrantRef(): GrantRef = error("not used")
            }
        val matrix =
            PermissionMatrix(
                listOf(PermissionDefinition(ingest, PermissionKind.WRITE)),
                mapOf(Role.AGENT_OPERATOR to mapOf(ingest to Allowance.WITHIN_SCOPE)),
            )
        val directory =
            object : EntityDirectory {
                override fun membership(entity: EntityRef): EntityMembership? = EntityMembership(entityTenant, null)

                override fun entitiesCoveredBy(scope: Scope): Set<EntityRef> = error("not used")
            }
        val elevations =
            object : Elevations {
                override fun findPending(ask: ElevationAsk): ElevationRecord? = error("not used")

                override fun insert(ask: ElevationAsk): ElevationRecord = error("not used")

                override fun lock(ref: String): ElevationRecord? = error("not used")

                override fun update(
                    before: ElevationRecord,
                    after: ElevationRecord,
                ) = error("not used")

                override fun findByRequester(requester: IdentityRef): List<ElevationRecord> = error("not used")
            }
        val overlay =
            OperatorOverlay(
                elevations,
                object : GrantAudit {
                    override fun record(entry: GrantAuditEntry) = error("not used")
                },
                object : PlatformRead {
                    override fun <T> read(block: () -> T): T = error("not used")
                },
                grants,
                matrix,
            )
        val authorise =
            Authorise(
                object : TransactionRunner {
                    override fun <T> inTransaction(block: () -> T): T = block()
                },
                grants,
                directory,
                matrix,
                AgentScope(
                    FakeConsents(live),
                    ScopeItemRequirements(mapOf(ingest to ScopeItem.SUBMIT_INVOICE_DATA)),
                ),
                Clock.fixed(moment, ZoneOffset.UTC),
                overlay,
            )
        return authorise.handle(operator, ingest, Target(client))
    }

    private fun submit(
        agent: String,
        status: ConsentStatus = ConsentStatus.LIVE,
    ) = consent(agent, items = setOf(ScopeItem.SUBMIT_INVOICE_DATA), status = status)

    @Test
    fun `INV-AC-17814 authorise allows the operator grant of the entity tenant on a consent naming that tenant`() {
        assertEquals(true, decide("T-1", submit("T-1")) is Decision.Allow)
    }

    @Test
    fun `INV-AC-17814 authorise refuses a grant of tenant U on an entity of tenant T though the consent names U`() {
        assertEquals(true, decide("U-1", submit("U-1")) is Decision.Deny)
    }

    @Test
    fun `INV-AC-17814 authorise refuses a grant of tenant U when the consent names the entity tenant`() {
        assertEquals(true, decide("U-1", submit("T-1")) is Decision.Deny)
    }

    @Test
    fun `INV-AC-17814 authorise refuses the entity tenant grant when the consent names the identity text`() {
        assertEquals(true, decide("T-1", submit("user:operator")) is Decision.Deny)
    }

    @Test
    fun `INV-AC-17814 authorise refuses after the consent is withdrawn`() {
        assertEquals(true, decide("T-1", submit("T-1", ConsentStatus.WITHDRAWN)) is Decision.Deny)
    }
}
