package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.authorise.AuthoriseGate
import com.dynokore.gst.access.application.authorise.AuthoriseOutcome
import com.dynokore.gst.access.application.authorise.OperatorOverlay
import com.dynokore.gst.access.application.authorise.PlatformRead
import com.dynokore.gst.access.application.authorise.StructureReadiness
import com.dynokore.gst.access.application.consent.AgentScope
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.application.elevation.ElevationAsk
import com.dynokore.gst.access.application.elevation.ElevationRecord
import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** INV-AC-9089 at the unit level: the gate answers NotReady before the structure is synced, never a Deny. */
class AuthoriseGateTest {
    private val tenant = TenantRef("T-1")
    private val principal = IdentityRef("user-1")
    private val view = Permission("report.view")
    private val live = EntityRef("ENT-LIVE")
    private val retired = EntityRef("ENT-RETIRED")
    private val unknown = EntityRef("ENT-UNKNOWN")
    private val now = Instant.parse("2026-11-06T00:00:00Z")

    private var synced = false
    private val directoryCalls = mutableListOf<EntityRef>()

    private val directory =
        object : EntityDirectory {
            override fun membership(entity: EntityRef): EntityMembership? {
                directoryCalls += entity
                return if (entity == live || entity == retired) EntityMembership(tenant, null) else null
            }

            override fun entitiesCoveredBy(scope: Scope): Set<EntityRef> = error("not used")
        }

    // the grant covers the live entity only, so the retired entity (known, but not granted) is denied
    private val grant =
        Grant.issue(
            GrantHeader(GrantRef("G-1"), tenant, principal),
            Role.TENANT_ADMIN,
            Scope.Entity(live),
            ValidityWindow(now.minusSeconds(3600), null),
        )

    private val grants =
        object : GrantRepository {
            override fun findByIdentity(identity: IdentityRef): List<Grant> = if (identity == principal) listOf(grant) else emptyList()

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

    private val matrix =
        PermissionMatrix(
            listOf(PermissionDefinition(view, PermissionKind.READ)),
            mapOf(Role.TENANT_ADMIN to mapOf(view to Allowance.PERMITTED)),
        )

    private val consents =
        object : ConsentRepository {
            override fun find(ref: ConsentRef): Consent? = error("not used")

            override fun lock(ref: ConsentRef): Consent? = error("not used")

            override fun findLiveForClient(client: EntityRef): Consent? = error("not used")

            override fun insert(consent: Consent) = error("not used")

            override fun update(
                before: Consent,
                after: Consent,
            ) = error("not used")

            override fun nextAuthorityVersion(): Long = error("not used")

            override fun nextRef(): ConsentRef = error("not used")
        }

    private val elevations =
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

    private val overlay =
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

    private val authorise =
        Authorise(
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            grants,
            directory,
            matrix,
            AgentScope(consents, ScopeItemRequirements(emptyMap())),
            Clock.fixed(now, ZoneOffset.UTC),
            overlay,
        )

    private val gate = AuthoriseGate({ synced }, authorise)

    private fun ask(entity: EntityRef) = gate.handle(principal, view, Target(entity))

    @Test
    fun `INV-AC-9089 not synced answers NotReady for live, unknown and retired entities and calls nothing`() {
        synced = false
        assertEquals(AuthoriseOutcome.NotReady, ask(live))
        assertEquals(AuthoriseOutcome.NotReady, ask(unknown))
        assertEquals(AuthoriseOutcome.NotReady, ask(retired))
        assertEquals(emptyList<EntityRef>(), directoryCalls)
    }

    @Test
    fun `INV-AC-9089 synced decides a granted live entity ALLOW`() {
        synced = true
        assertEquals(AuthoriseOutcome.Decided(Decision.Allow(Role.TENANT_ADMIN)), ask(live))
    }

    @Test
    fun `INV-AC-9089 synced decides an unknown entity DENY NO_ENTITY, not NotReady`() {
        synced = true
        assertEquals(AuthoriseOutcome.Decided(Decision.Deny("NO_ENTITY")), ask(unknown))
    }

    @Test
    fun `INV-AC-9089 synced decides a retired entity DENY`() {
        synced = true
        assertEquals(AuthoriseOutcome.Decided(Decision.Deny("NO_GRANT")), ask(retired))
    }

    @Test
    fun `INV-AC-9089 a flip from synced to not synced makes the second call NotReady`() {
        synced = true
        assertEquals(AuthoriseOutcome.Decided(Decision.Allow(Role.TENANT_ADMIN)), ask(live))
        val callsAfterFirst = directoryCalls.size
        synced = false
        assertEquals(AuthoriseOutcome.NotReady, ask(live))
        assertEquals(callsAfterFirst, directoryCalls.size)
    }

    @Test
    fun `INV-AC-9089 NotReady is not a Deny and the outcome has exactly two members`() {
        assertFalse((AuthoriseOutcome.NotReady as Any) is Decision.Deny)
        assertEquals(
            setOf("Decided", "NotReady"),
            AuthoriseOutcome::class.sealedSubclasses.map { it.simpleName }.toSet(),
        )
        val covered =
            when (AuthoriseOutcome.NotReady as AuthoriseOutcome) {
                is AuthoriseOutcome.Decided -> "decided"
                AuthoriseOutcome.NotReady -> "not-ready"
            }
        assertEquals("not-ready", covered)
        assertEquals(StructureReadiness { true }.isSynced(), true)
    }
}
