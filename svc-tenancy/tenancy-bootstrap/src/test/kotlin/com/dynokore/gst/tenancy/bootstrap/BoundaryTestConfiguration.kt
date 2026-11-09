package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.adapters.installation.ReplicaInstallationAuthority
import com.dynokore.gst.tenancy.adapters.workspace.ReplicaTenantAuthority
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.application.installation.InstallationAuthority
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverPermissions
import com.dynokore.gst.tenancy.application.workspace.TenantAuthority
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import com.dynokore.gst.tenancy.domain.SandboxCertificationPermissions
import com.dynokore.platform.configresolver.AcknowledgeRelaxationCommand
import com.dynokore.platform.configresolver.BackdatingFloor
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.RelaxationStatus
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.StagedOutboxRow
import com.dynokore.platform.serviceruntime.policy.RequestBoundSetterPermission
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Captures what the outbox writer returned, so the tests can feed the replica exactly what the service published. */
class CapturedEvents {
    val envelopes = CopyOnWriteArrayList<Envelope>()
}

class CapturingOutboxWriter(
    private val delegate: OutboxWriter,
    private val captured: CapturedEvents,
) : OutboxWriter {
    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> = delegate.append(events, context).also { captured.envelopes.addAll(it) }

    /** The TENANT event is staged and written by the definer function (INV-Q-16720 A): captured as it is staged. */
    override fun stage(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<StagedOutboxRow> =
        delegate.stage(events, context).also { rows ->
            captured.envelopes.addAll(rows.map { it.envelope })
        }
}

/** Where a test role grant applies: the tenant and the grant scope. */
data class GrantSite(
    val tenant: TenantId = TestPeople.TENANT,
    val scope: GrantScope = GrantScope.ENTITY,
)

/**
 * The people of a test: who calls, and which roles each person holds for which entity (the authority replica of the
 * service, here a table). One bean answers the caller and authority ports of the elections: the permissions follow
 * the roles as the
 * RolePermissions cells do (CONFIRM is read from the generated cells); APPROVE is
 * answered by a real replica holding role grants.
 */
class TestPeople :
    CallerSource,
    ActionAuthority {
    @Volatile
    var caller: String = "identity:USR-001"
    private val granted = ConcurrentHashMap<Pair<String, String>, Set<String>>()
    private val clock = Clock.systemUTC()
    internal val replica = AuthorityReplica(clock, TestOperationRoles.map).also { it.onSnapshotComplete(0) }
    internal val installationReplica =
        AuthorityReplica(clock, TestOperationRoles.privateMap).also { it.onSnapshotComplete(0) }
    private val version = AtomicLong()
    private val approvals =
        ReplicaActionAuthority(
            replica,
            object : TenantContext {
                override fun current() = TENANT

                override fun currentActor(): String? = null

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            },
            clock,
        )

    /**
     * A role grant of [role] on one entity, fed to a real [AuthorityReplica] through its own ingest of the role-grant
     * record (INV-NOTE-10639): the replica decides from the roles the generated matrix lists for the permission key.
     */
    fun grantRole(
        person: String,
        entityRef: String,
        role: String,
        validUntil: java.time.Instant? = null,
        site: GrantSite = GrantSite(),
    ): String {
        val grantRef = "GR-${UUID.randomUUID()}"
        val grant = RoleGrant(role, site.scope, entityRef, clock.instant().minusSeconds(60), validUntil)
        feed(replica, site.tenant, person, AuthorityRecord.RoleGrantRecord(grantRef, RoleGrantState.ACTIVE, grant))
        return grantRef
    }

    private val permissions = ConcurrentHashMap<Pair<String, String>, Set<String>>()

    /**
     * A permission held on one entity for a key the operation-role matrix of the catalogue does not list
     * (`sandbox-certification.record`, catalogue gap reported in the MR): the test answers by the key, never by a role
     * name.
     */
    fun grantPermission(
        person: String,
        entityRef: String,
        permission: String,
    ) {
        permissions.merge(person to entityRef, setOf(permission)) { old, new -> old + new }
    }

    /** Removes the role grant [grantRef] written by [grantRole]: a revoked role takes effect at the next request. */
    fun revokeRole(
        person: String,
        entityRef: String,
        role: String,
        grantRef: String,
    ) {
        val grant = RoleGrant(role, GrantScope.ENTITY, entityRef, clock.instant().minusSeconds(60))
        feed(replica, TENANT, person, AuthorityRecord.RoleGrantRecord(grantRef, RoleGrantState.REMOVED, grant))
    }

    private fun feed(
        target: AuthorityReplica,
        tenant: TenantId,
        person: String,
        record: AuthorityRecord.RoleGrantRecord,
    ) {
        target.accept(AuthorityChanged(tenant, person, version.incrementAndGet(), record))
        target.observeHead(version.get())
    }

    /** A role grant of [role] at TENANT scope of [tenant], decided through `decideTenant` (INV-Q-10027). */
    fun grantTenantRole(
        tenant: TenantId,
        person: String,
        role: String,
    ) {
        grantRole(person, tenant.value.toString(), role, site = GrantSite(tenant, GrantScope.TENANT))
    }

    /** A role grant of [role] at scope INSTALLATION under [TenantId.PLATFORM], fed to the installation replica. */
    fun grantInstallationRole(
        person: String,
        role: String,
    ) {
        val grant = RoleGrant(role, GrantScope.INSTALLATION, null, clock.instant().minusSeconds(60))
        val record = AuthorityRecord.RoleGrantRecord("GR-${UUID.randomUUID()}", RoleGrantState.ACTIVE, grant)
        feed(installationReplica, TenantId.PLATFORM, person, record)
    }

    /** Every permission key the service asked about, in order: the keys are compared with the catalogue by equality. */
    val asked = CopyOnWriteArrayList<String>()

    fun grant(
        person: String,
        entityRef: String,
        vararg roles: String,
    ) {
        granted[person to entityRef] = roles.toSet()
    }

    override fun actor(): String = caller

    override fun holds(
        person: String,
        entityRef: String,
        action: String,
    ): Boolean {
        asked += action
        val roles = granted[person to entityRef].orEmpty()
        return when (action) {
            ElectionPermissions.MANAGE -> {
                // a role fake for older tests, and the real key decision over grants fed through grantRole
                ElectionRoles.ENTITY_ADMIN in roles || approvals.holds(person, entityRef, action)
            }

            ElectionPermissions.CONFIRM -> {
                roles.any { it in CONFIRMING }
            }

            PeriodPermissions.FILING_FREQUENCY_CHANGE -> {
                ElectionRoles.ENTITY_ADMIN in roles
            }

            PeriodPermissions.PERIOD_CONFIRM -> {
                roles.any { it in PERIOD_CONFIRMING }
            }

            PeriodPermissions.EXTENSION_RECORD -> {
                roles.any { it in EXTENDING }
            }

            PeriodPermissions.PACK_DELTA_VIEW -> {
                roles.any { it in PACK_DELTA_VIEWING }
            }

            ElectionPermissions.APPROVE -> {
                approvals.holds(
                    person,
                    entityRef,
                    action,
                )
            }

            SandboxCertificationPermissions.RECORD,
            MigrationCutoverPermissions.RECORD,
            MigrationCutoverPermissions.VIEW,
            -> {
                action in permissions[person to entityRef].orEmpty()
            }

            else -> {
                false
            }
        }
    }

    internal companion object {
        /** The roles the catalogue permits `period-extension.record`, read through RolePermissions (INV-Q-16671). */
        val EXTENDING: Set<String> =
            DeploymentForm.entries
                .flatMap { form ->
                    RolePermissions
                        .cells(form)
                        .filterValues { it[PeriodPermissions.EXTENSION_RECORD] == RoleAllowance.PERMITTED }
                        .keys
                }.toSet()

        /** The roles the catalogue permits `calendar-pack-delta.view`, read through RolePermissions (INV-T-16829). */
        val PACK_DELTA_VIEWING: Set<String> =
            DeploymentForm.entries
                .flatMap { form ->
                    RolePermissions
                        .cells(form)
                        .filterValues { it[PeriodPermissions.PACK_DELTA_VIEW] == RoleAllowance.PERMITTED }
                        .keys
                }.toSet()
        val PERIOD_CONFIRMING = setOf(ElectionRoles.ENTITY_ADMIN, ElectionRoles.TAX_APPROVER)
        val TENANT = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
        val CONFIRMING: Set<String> =
            DeploymentForm.entries
                .flatMap { form ->
                    RolePermissions
                        .cells(form)
                        .filterValues { it[ElectionPermissions.CONFIRM] == RoleAllowance.PERMITTED }
                        .keys
                }.toSet()
    }
}

/** The entity facts of a test; a fact not set answers NOT_RECORDED, as the default binding does. */
class TestFacts : EntityFactSource {
    private val answers = ConcurrentHashMap<Pair<String, String>, FactAnswer>()
    val asked = CopyOnWriteArrayList<String>()

    fun set(
        entityRef: String,
        fact: String,
        answer: FactAnswer,
    ) {
        answers[entityRef to fact] = answer
    }

    private fun answer(
        entityRef: String,
        fact: String,
    ): FactAnswer {
        asked += "$fact:$entityRef"
        return answers[entityRef to fact] ?: FactAnswer.NOT_RECORDED
    }

    override fun recordsPurchases(entityRef: String) = answer(entityRef, "recordsPurchases")

    override fun hasForeignCurrencyDocuments(entityRef: String) = answer(entityRef, "hasForeignCurrencyDocuments")

    override fun claimsInputTaxOnPostingBasis(entityRef: String) = answer(entityRef, "claimsInputTaxOnPostingBasis")
}

@Configuration(proxyBeanMethods = false)
class BoundaryTestConfiguration {
    @Bean
    @Primary
    fun testPeople(): TestPeople = TestPeople()

    /** The double of the entity facts; `inv.test.stored-facts=true` keeps the stored binding (INV-AC-9769). */
    @Bean
    @Primary
    @ConditionalOnProperty("inv.test.stored-facts", havingValue = "false", matchIfMissing = true)
    fun testFacts(): TestFacts = TestFacts()

    /** The production binding over the replica of [TestPeople]: no grant, no access (INV-Q-10027). */
    @Bean
    @Primary
    fun testTenantAuthority(
        people: TestPeople,
        tenants: TenantContext,
    ): TenantAuthority = ReplicaTenantAuthority(people.replica, tenants)

    /** The production binding over the installation replica of [TestPeople] (PRIVATE matrix): no grant, no access. */
    @Bean
    @Primary
    fun testInstallationAuthority(people: TestPeople): InstallationAuthority =
        ReplicaInstallationAuthority(people.installationReplica)

    @Bean
    fun capturedEvents(): CapturedEvents = CapturedEvents()

    /**
     * A healthy authority replica with no agent consent: an entity-level target is not delegated, a client-entity
     * target always is (as product-runtime's probe answers them); the VP-62 tests of INV-T-4930 need the first.
     */
    @Bean
    fun testDelegationProbe(): DelegationProbe =
        DelegationProbe { target, _ -> target.level == com.dynokore.platform.configresolver.ScopeLevel.CLIENT_ENTITY }

    /** The stand-in for the owning service's floor (INV-Q-3140): no value may take effect before 2026-01-01. */
    @Bean
    fun testBackdatingFloor(): BackdatingFloor = BackdatingFloor { _, _ -> LocalDate.of(2026, 1, 1) }

    @Bean
    fun capturingOutbox(captured: CapturedEvents): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is OutboxWriter &&
                    bean !is CapturingOutboxWriter
                ) {
                    CapturingOutboxWriter(bean, captured)
                } else {
                    bean
                }
        }
}

/** The operation-role matrix of the replicas of the tests: read from the generated catalogue, never written by hand. */
object TestOperationRoles {
    val form: DeploymentForm = DeploymentForm.SAAS

    val map: OperationRoleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(form, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** The matrix of the PRIVATE form, where the installation-scope keys (`vp-52.set`) have a holder. */
    val privateMap: OperationRoleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.PRIVATE, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** The roles the PRIVATE matrix lists for [key], as a set. */
    fun privateHolders(key: String): Set<String> = privateMap.rolesFor(key).keys

    /** The roles the catalogue lists for [key], as a set. */
    fun holders(key: String): Set<String> = map.rolesFor(key).keys

    /** The one role that holds [key]; the call fails when the catalogue lists none or several. */
    fun soleHolder(key: String): String = holders(key).single()
}
