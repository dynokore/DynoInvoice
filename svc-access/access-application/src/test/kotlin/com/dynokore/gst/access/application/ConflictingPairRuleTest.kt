package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.AuthorityGrantRecord
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleEffects
import com.dynokore.gst.access.application.grant.GrantRoleOutcome
import com.dynokore.gst.access.application.grant.GrantRoleRules
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.idp.GroupGrantReconciler
import com.dynokore.gst.access.application.idp.IdpGroupMapping
import com.dynokore.gst.access.application.idp.IdpProvisioningEffects
import com.dynokore.gst.access.application.idp.MappedGrants
import com.dynokore.gst.access.application.separation.ConflictingPairPolicy
import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.application.separation.RolePair
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
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.gst.access.domain.role.TenantGrantableRoles
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * INV-AC-12355, INV-AC-12355 (INV-T-12356, INV-REQ-9271): the rule of VP-82 in `grantRole` and in group provisioning,
 * on in-memory collaborators. The listed pairs are read after the identity lock and after the built-in pair test.
 */
class ConflictingPairRuleTest {
    private val tenant = TenantRef("T-1")
    private val scope: Scope = Scope.Tenant(tenant)
    private val window = ValidityWindow(Instant.parse("2026-01-01T00:00:00Z"), null)
    private val person = IdentityRef("IDN-P")
    private val other = IdentityRef("IDN-Q")

    private class Memory : GrantRepository {
        val rows = mutableListOf<Grant>()
        var next = 0L

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
        ) = error("not used")

        override fun lockIdentity(
            identity: IdentityRef,
            kind: GranteeKind,
        ) = Unit

        override fun nextAuthorityVersion() = ++next

        override fun nextGrantRef() = GrantRef("G-${++next}")
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

    private val directory =
        object : EntityDirectory {
            override fun membership(entity: EntityRef): EntityMembership? = null

            override fun entitiesCoveredBy(scope: Scope): Set<EntityRef> = emptySet()
        }

    private class Writes :
        AuthorityChangePublisher,
        GrantAudit {
        val events = mutableListOf<AuthorityGrantRecord>()
        val audit = mutableListOf<GrantAuditEntry>()

        override fun publish(records: List<AuthorityGrantRecord>) {
            events += records
        }

        override fun record(entry: GrantAuditEntry) {
            audit += entry
        }
    }

    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private fun grantRows(
        repo: Memory,
        who: IdentityRef,
        vararg roles: Role,
        status: GrantStatus = GrantStatus.ACTIVE,
    ) = roles.forEach { role ->
        repo.insert(
            Grant.issue(
                GrantHeader(repo.nextGrantRef(), tenant, who),
                role,
                scope,
                window,
                GrantState(status, GrantSource.BY_HAND, 0),
            ),
        )
    }

    private fun pairsOf(vararg pairs: Pair<Role, Role>) =
        ConflictingRolePairs { pairs.map { RolePair.of(it.first, it.second) }.toSet() }

    private fun useCase(
        repo: Memory,
        writes: Writes,
        pairs: ConflictingRolePairs,
    ) = GrantRoleUseCase(
        tx,
        repo,
        directory,
        PermissionMatrix(emptyList(), emptyMap()),
        GrantRoleEffects(writes, writes),
        GrantRoleRules(RoleCatalogue.ALL, noAck, { SoleOperatorScope.ONE_HOLDER_WITHDRAW_BLOCKED }, pairs),
    )

    private fun grant(
        useCase: GrantRoleUseCase,
        who: IdentityRef,
        role: Role,
    ) = useCase.handle(GrantRoleCommand(tenant, "user:admin", who, GranteeKind.STAFF, role.wire, scope, window))

    private val builtIn = setOf(Role.SECURITY_OFFICER, Role.TENANT_ADMIN)

    @Test
    fun `INV-AC-12355 under the default every held role and granted role has the outcome of main`() {
        Role.entries.forEach { held ->
            Role.entries.forEach { granted ->
                val repo = Memory()
                val writes = Writes()
                grantRows(repo, person, held)
                val outcome = grant(useCase(repo, writes, ConflictingRolePairs { emptySet() }), person, granted)
                val expected: Class<*> =
                    when {
                        TenantGrantableRoles.refuses(granted) -> {
                            GrantRoleOutcome.OperatorRoleRefused::class.java
                        }

                        held != granted && setOf(held, granted) == builtIn -> {
                            GrantRoleOutcome.SoleOperatorAckRequired::class.java
                        }

                        else -> {
                            GrantRoleOutcome.Granted::class.java
                        }
                    }
                assertInstanceOf(expected, outcome, "held ${held.wire}, granted ${granted.wire}")
            }
        }
    }

    @Test
    fun `INV-AC-12355 a grant that completes a listed pair is refused in both orders and writes nothing`() {
        val orders = listOf(Role.TAX_OPERATOR to Role.FINANCE_VIEWER, Role.FINANCE_VIEWER to Role.TAX_OPERATOR)
        orders.forEach { (held, granted) ->
            val repo = Memory()
            val writes = Writes()
            grantRows(repo, person, held)
            val pairs = pairsOf(Role.TAX_OPERATOR to Role.FINANCE_VIEWER)
            val outcome = grant(useCase(repo, writes, pairs), person, granted)
            assertEquals(GrantRoleOutcome.ConflictingPairRefused, outcome)
            assertEquals(1, repo.rows.size)
            assertTrue(writes.events.isEmpty())
            assertTrue(writes.audit.isEmpty())
        }
    }

    @Test
    fun `INV-AC-12355 a person holding neither role, an unlisted role and a revoked grant are accepted`() {
        val pairs = pairsOf(Role.TAX_OPERATOR to Role.FINANCE_VIEWER)
        val repo = Memory()
        val writes = Writes()
        val use = useCase(repo, writes, pairs)
        assertInstanceOf(GrantRoleOutcome.Granted::class.java, grant(use, person, Role.FINANCE_VIEWER))
        assertInstanceOf(GrantRoleOutcome.Granted::class.java, grant(use, person, Role.AUDITOR))
        grantRows(repo, other, Role.TAX_OPERATOR, status = GrantStatus.REVOKED)
        assertInstanceOf(GrantRoleOutcome.Granted::class.java, grant(use, other, Role.FINANCE_VIEWER))
        assertEquals(4, repo.rows.size)
    }

    @Test
    fun `INV-AC-12355 a person already holding both roles keeps both and receives an unrelated grant`() {
        val repo = Memory()
        val writes = Writes()
        grantRows(repo, person, Role.TAX_OPERATOR, Role.FINANCE_VIEWER)
        val use = useCase(repo, writes, pairsOf(Role.TAX_OPERATOR to Role.FINANCE_VIEWER))
        assertInstanceOf(GrantRoleOutcome.Granted::class.java, grant(use, person, Role.AUDITOR))
        assertEquals(
            setOf(Role.TAX_OPERATOR, Role.FINANCE_VIEWER, Role.AUDITOR),
            repo.findByIdentity(person).map { it.role }.toSet(),
        )
    }

    @Test
    fun `INV-AC-12355 a listed pair does not lift the built-in pair, which keeps its own outcome`() {
        val repo = Memory()
        grantRows(repo, person, Role.SECURITY_OFFICER)
        val use = useCase(repo, Writes(), pairsOf(Role.TAX_OPERATOR to Role.FINANCE_VIEWER))
        assertEquals(GrantRoleOutcome.SoleOperatorAckRequired, grant(use, person, Role.TENANT_ADMIN))
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun `INV-AC-12355 a failed or unknown read fails the request and nothing is written`() {
        val repo = Memory()
        val writes = Writes()
        grantRows(repo, person, Role.AUDITOR)
        val failing = ConflictingRolePairs { throw IllegalStateException("store down") }
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                grant(useCase(repo, writes, failing), person, Role.TAX_OPERATOR)
            }
        assertEquals("store down", thrown.message)
        assertEquals(1, repo.rows.size)
        assertTrue(writes.events.isEmpty())
        assertTrue(writes.audit.isEmpty())
    }

    @Test
    fun `INV-AC-12355 provisioning skips the member that completes a listed pair and provisions the others`() {
        val repo = Memory()
        val writes = Writes()
        grantRows(repo, person, Role.TAX_OPERATOR)
        val effects =
            IdpProvisioningEffects(
                writes,
                writes,
                RoleCatalogue.ALL,
                MappedGrants { emptyList() },
                pairsOf(Role.TAX_OPERATOR to Role.FINANCE_VIEWER),
            )
        val mapping = IdpGroupMapping("M-1", "grp", Role.FINANCE_VIEWER, scope, LocalDate.parse("2026-01-01"), null)
        GroupGrantReconciler(repo, directory, PermissionMatrix(emptyList(), emptyMap()), effects)
            .reconcile("user:admin", tenant, mapping, setOf(person, other))
        assertEquals(listOf(Role.TAX_OPERATOR), repo.findByIdentity(person).map { it.role })
        assertEquals(listOf(Role.FINANCE_VIEWER), repo.findByIdentity(other).map { it.role })
        assertEquals(1, writes.audit.size)
    }

    @Test
    fun `INV-AC-12355 a listed pair is a set of two different roles`() {
        assertEquals(RolePair.of(Role.TENANT_ADMIN, Role.AUDITOR), RolePair.of(Role.AUDITOR, Role.TENANT_ADMIN))
        assertThrows(IllegalArgumentException::class.java) { RolePair.of(Role.AUDITOR, Role.AUDITOR) }
    }

    @Test
    fun `INV-AC-12355 the pair policy decides both orders, an unlisted role, a role not held and equal roles`() {
        val pairs = setOf(RolePair.of(Role.TAX_OPERATOR, Role.FINANCE_VIEWER))
        val policy = ConflictingPairPolicy
        assertTrue(policy.completesListedPair(pairs, setOf(Role.TAX_OPERATOR), Role.FINANCE_VIEWER))
        assertTrue(policy.completesListedPair(pairs, setOf(Role.FINANCE_VIEWER), Role.TAX_OPERATOR))
        assertTrue(!policy.completesListedPair(pairs, setOf(Role.TAX_OPERATOR), Role.AUDITOR))
        assertTrue(!policy.completesListedPair(pairs, setOf(Role.AUDITOR), Role.FINANCE_VIEWER))
        assertTrue(!policy.completesListedPair(emptySet(), setOf(Role.TAX_OPERATOR), Role.FINANCE_VIEWER))
        assertThrows(IllegalArgumentException::class.java) { RolePair.of(Role.TAX_OPERATOR, Role.TAX_OPERATOR) }
    }
}
