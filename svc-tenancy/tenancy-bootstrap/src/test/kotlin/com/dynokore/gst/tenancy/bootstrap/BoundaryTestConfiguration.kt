package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import com.dynokore.platform.configresolver.BackdatingFloor
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.config.BeanPostProcessor
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
}

/**
 * The people of a test: who calls, and which roles each person holds for which entity (the authority replica of the
 * service, here a table). One bean answers the caller and authority ports of the elections: the permissions follow
 * the roles as the
 * RolePermissions cells do (CONFIRM is read from the generated cells); APPROVE is
 * answered by a real replica holding permission grants only.
 */
class TestPeople :
    CallerSource,
    ActionAuthority {
    @Volatile
    var caller: String = "identity:USR-001"
    private val granted = ConcurrentHashMap<Pair<String, String>, Set<String>>()
    private val clock = Clock.systemUTC()
    private val replica = AuthorityReplica(clock).also { it.onSnapshotComplete(0) }
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

    /** A permission grant on one entity, held in a real [AuthorityReplica] (permissions are not derived from roles). */
    fun grantPermission(
        person: String,
        entityRef: String,
        permission: String,
    ) {
        val now = clock.instant()
        replica.accept(
            AuthorityChanged(
                TENANT,
                person,
                version.incrementAndGet(),
                AuthorityRecord.GrantRecord(
                    entityRef,
                    listOf(Grant(permission, ScopeLevel.ENTITY, entityRef, now.minusSeconds(60), null)),
                ),
            ),
        )
        replica.observeHead(version.get())
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
            ElectionPermissions.MANAGE -> ElectionRoles.ENTITY_ADMIN in roles
            ElectionPermissions.CONFIRM -> roles.any { it in CONFIRMING }
            PeriodPermissions.FILING_FREQUENCY_CHANGE -> ElectionRoles.ENTITY_ADMIN in roles
            PeriodPermissions.PERIOD_CONFIRM -> roles.any { it in PERIOD_CONFIRMING }
            PeriodPermissions.EXTENSION_RECORD -> roles.any { it in EXTENDING }
            ElectionPermissions.APPROVE -> approvals.holds(person, entityRef, action)
            else -> false
        }
    }

    private companion object {
        /** The roles the catalogue permits `period-extension.record`, read through RolePermissions (INV-Q-16671). */
        val EXTENDING: Set<String> =
            DeploymentForm.entries
                .flatMap { form ->
                    RolePermissions
                        .cells(form)
                        .filterValues { it[PeriodPermissions.EXTENSION_RECORD] == RoleAllowance.PERMITTED }
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

    @Bean
    @Primary
    fun testFacts(): TestFacts = TestFacts()

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
