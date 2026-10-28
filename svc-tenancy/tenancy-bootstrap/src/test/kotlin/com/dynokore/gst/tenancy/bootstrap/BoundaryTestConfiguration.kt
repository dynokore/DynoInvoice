package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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
 * RolePermissions cells do (MANAGE: entity.admin; CONFIRM: entity.admin, tenant.admin, tax.approver).
 */
class TestPeople :
    CallerSource,
    ActionAuthority {
    @Volatile
    var caller: String = "identity:USR-001"
    private val granted = ConcurrentHashMap<Pair<String, String>, Set<String>>()

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
            else -> false
        }
    }

    private companion object {
        val PERIOD_CONFIRMING = setOf(ElectionRoles.ENTITY_ADMIN, ElectionRoles.TAX_APPROVER)
        val CONFIRMING = setOf(ElectionRoles.ENTITY_ADMIN, ElectionRoles.TENANT_ADMIN, ElectionRoles.TAX_APPROVER)
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
