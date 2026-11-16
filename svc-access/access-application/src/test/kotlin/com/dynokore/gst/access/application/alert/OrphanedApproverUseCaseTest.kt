package com.dynokore.gst.access.application.alert

import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleEffects
import com.dynokore.gst.access.application.grant.GrantRoleRules
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.application.idp.GroupGrantReconciler
import com.dynokore.gst.access.application.idp.IdpGroupMapping
import com.dynokore.gst.access.application.idp.IdpProvisioningEffects
import com.dynokore.gst.access.application.idp.MappedGrants
import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.application.separation.SoleOperatorAck
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.application.separation.SoleOperatorScope
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** INV-AC-23854: ACC-001 at the level of the use cases, over in-memory ports. */
class OrphanedApproverUseCaseTest {
    private val tenant = TenantRef("T-1")
    private val e1 = EntityRef("E1")
    private val e2 = EntityRef("E2")
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val window = ValidityWindow(Instant.parse("2026-01-01T00:00:00Z"), null)
    private val people = (1..6).map { IdentityRef("IDN-$it") }

    private val rows = mutableListOf<Grant>()
    private var counter = 0L
    private val raised = mutableListOf<Pair<String, String>>()
    private val open = mutableSetOf<EntityRef>()
    private var alertFails = false

    private val repo =
        object : GrantRepository {
            override fun findByIdentity(identity: IdentityRef) = rows.filter { it.identity == identity }

            override fun find(ref: GrantRef) = rows.firstOrNull { it.ref == ref }

            override fun findByRepeatKey(
                identity: IdentityRef,
                roleWire: String,
                scope: Scope,
            ) = rows.firstOrNull { it.identity == identity && it.role.wire == roleWire && it.scope == scope }

            override fun insert(grant: Grant) {
                rows += grant
            }

            override fun update(
                before: Grant,
                after: Grant,
            ) {
                rows[rows.indexOf(before)] = after
            }

            override fun lockIdentity(
                identity: IdentityRef,
                kind: GranteeKind,
            ) = Unit

            override fun nextAuthorityVersion() = ++counter

            override fun nextGrantRef() = GrantRef("N-${++counter}")
        }

    private val directory =
        object : EntityDirectory {
            override fun membership(entity: EntityRef) = EntityMembership(tenant, null)

            override fun entitiesCoveredBy(scope: Scope): Set<EntityRef> =
                when (scope) {
                    is Scope.Entity -> setOf(scope.entity)
                    else -> setOf(e1, e2)
                }
        }

    private val state =
        object : OrphanAlertState {
            override fun tryOpen(entity: EntityRef) = open.add(entity)

            override fun close(entity: EntityRef) {
                open.remove(entity)
            }

            override fun openEntities(): Set<EntityRef> = open.toSet()
        }

    private val orphans =
        OrphanedApprovers(
            { entity, at ->
                rows.count {
                    it.role == Role.TAX_APPROVER && it.status == GrantStatus.ACTIVE && it.window.isValidAt(at) &&
                        entity in directory.entitiesCoveredBy(it.scope)
                }
            },
            { entity -> rows.any { it.role == Role.TAX_APPROVER && entity in directory.entitiesCoveredBy(it.scope) } },
            state,
            directory,
            { code, scope ->
                if (alertFails) error("alert port down")
                raised += code to scope
            },
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private val sink =
        object : AuthorityChangePublisher, GrantAudit {
            override fun publish(changes: List<RoleGrantChange>) = Unit

            override fun record(entry: GrantAuditEntry) = Unit
        }

    /** A transaction that restores the grant rows when the block throws, as the database does. */
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                val snapshot = rows.toList()
                return try {
                    block()
                } catch (e: IllegalStateException) {
                    rows.clear()
                    rows += snapshot
                    throw e
                }
            }
        }

    private val noAck =
        object : SoleOperatorAcks {
            override fun findActive(): SoleOperatorAck? = null

            override fun find(ref: String): SoleOperatorAck? = null

            override fun lockActive(): SoleOperatorAck? = null

            override fun holders(role: Role): Set<IdentityRef> = emptySet()

            override fun insert(compensatingControls: String): SoleOperatorAck = error("not used")

            override fun withdraw(ref: String) = error("not used")
        }

    private val revoke = RevokeGrantUseCase(tx, repo, sink, sink, orphans)
    private val grantRole =
        GrantRoleUseCase(
            tx,
            repo,
            GrantRoleEffects(sink, sink),
            GrantRoleRules(
                RoleCatalogue.ALL,
                noAck,
                { SoleOperatorScope.ONE_HOLDER_WITHDRAW_BLOCKED },
                ConflictingRolePairs { emptySet() },
            ),
            orphans,
        )

    private fun seed(
        ref: String,
        who: IdentityRef,
        role: Role,
        scope: Scope,
        state: GrantState = GrantState(GrantStatus.ACTIVE, GrantSource.BY_HAND, 0),
    ) = rows.add(
        Grant.issue(
            GrantHeader(GrantRef(ref), tenant, who),
            role,
            scope,
            window,
            state,
        ),
    )

    private fun command(roleWire: String) =
        GrantRoleCommand(tenant, "user:admin", people[3], GranteeKind.STAFF, roleWire, Scope.Entity(e1), window)

    private fun revoke(ref: String) = revoke.handle("user:admin", GrantRef(ref))

    private fun seedStandard() {
        seed("G1", people[0], Role.TAX_APPROVER, Scope.Entity(e1))
        seed("G2", people[1], Role.TAX_APPROVER, Scope.Entity(e2))
        seed("G3", people[2], Role.TAX_APPROVER, Scope.Entity(e2))
    }

    private val alertE1 = "ACC-001" to "entity:E1"

    @Test
    fun `INV-AC-23854 the sequence of revoke, repeat, grant, revoke and the E2 pair records the stated alerts`() {
        seedStandard()
        revoke("G1")
        assertEquals(listOf(alertE1), raised)
        assertEquals(setOf(e1), open)
        revoke("G1")
        assertEquals(listOf(alertE1), raised)

        grantRole.handle(
            command("tax.approver"),
        )
        assertEquals(emptySet<EntityRef>(), open)
        assertEquals(listOf(alertE1), raised)

        val granted = rows.last()
        revoke(granted.ref.value)
        assertEquals(listOf(alertE1, alertE1), raised)

        revoke("G2")
        assertEquals(2, raised.size)
        revoke("G3")
        assertEquals(listOf(alertE1, alertE1, "ACC-001" to "entity:E2"), raised)
    }

    @Test
    fun `INV-AC-23854 a tenant-wide revoke alerts only the covered entities left without an approver`() {
        seed("GT", people[0], Role.TAX_APPROVER, Scope.Tenant(tenant))
        seed("G2", people[1], Role.TAX_APPROVER, Scope.Entity(e2))
        revoke("GT")
        assertEquals(listOf(alertE1), raised)
    }

    @Test
    fun `INV-AC-23854 the revoke of another role records nothing`() {
        seedStandard()
        seed("GA", people[3], Role.TENANT_ADMIN, Scope.Entity(e1))
        revoke("GA")
        assertEquals(emptyList<Pair<String, String>>(), raised)
        assertEquals(emptySet<EntityRef>(), open)
    }

    @Test
    fun `INV-AC-23854 a directory group reconciliation that removes the approver records the same single alert`() {
        val mapped = GrantState(GrantStatus.ACTIVE, GrantSource.DIRECTORY_GROUPS, 0, "M-1")
        seed("GM", people[4], Role.TAX_APPROVER, Scope.Entity(e1), mapped)
        val effects =
            IdpProvisioningEffects(
                sink,
                sink,
                RoleCatalogue.ALL,
                MappedGrants { ref -> rows.filter { it.sourceRef == ref && it.status != GrantStatus.REVOKED } },
                ConflictingRolePairs { emptySet() },
                orphans,
            )
        val from = LocalDate.parse("2026-01-01")
        val mapping = IdpGroupMapping("M-1", "grp", Role.TAX_APPROVER, Scope.Entity(e1), from, null)
        GroupGrantReconciler(repo, effects).reconcile("user:admin", tenant, mapping, emptySet())
        assertEquals(listOf(alertE1), raised)
        assertEquals(setOf(e1), open)
    }

    @Test
    fun `INV-AC-23854 a failing alert port leaves the grant unrevoked`() {
        seedStandard()
        val before = rows.toList()
        alertFails = true
        assertThrows(IllegalStateException::class.java) { revoke("G1") }
        assertEquals(before, rows)
        assertEquals(GrantStatus.ACTIVE, repo.find(GrantRef("G1"))?.status)
    }

    @Test
    fun `INV-AC-23854 an entity that never held an approver records nothing when another role is granted`() {
        grantRole.handle(
            command("auditor"),
        )
        assertEquals(emptyList<Pair<String, String>>(), raised)
    }
}
