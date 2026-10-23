package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.consent.AgentTenancyConstraints
import com.dynokore.gst.access.adapters.consent.VariationPointAgentTenancy
import com.dynokore.gst.access.adapters.events.AuthorityChangedOutbox
import com.dynokore.gst.access.adapters.landing.RolePrecedenceConstraints
import com.dynokore.gst.access.adapters.persistence.JooqConsents
import com.dynokore.gst.access.adapters.persistence.JooqHandoverStatements
import com.dynokore.gst.access.adapters.persistence.JooqLiveConsentProbe
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.consent.AgentScope
import com.dynokore.gst.access.application.consent.AgentTenancy
import com.dynokore.gst.access.application.consent.ConsentChangePublisher
import com.dynokore.gst.access.application.consent.ConsentEffects
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.GrantConsentUseCase
import com.dynokore.gst.access.application.consent.HandoverPorts
import com.dynokore.gst.access.application.consent.HandoverStatements
import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.application.consent.UnsubmittedDocuments
import com.dynokore.gst.access.application.consent.WithdrawConsentUseCase
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18, continued: agent consent (INV-REQ-713, INV-REQ-912, INV-REQ-913, INV-REQ-955) and agent tenancy
 * (INV-REQ-2757), constructed here so that [UseCases] stays within the function limit.
 */
@Configuration(proxyBeanMethods = false)
class ConsentUseCases {
    @Bean
    fun consentRepository(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): ConsentRepository = JooqConsents(dsl, tenants, clock)

    @Bean
    fun handoverStatements(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): HandoverStatements = JooqHandoverStatements(dsl, tenants, clock)

    /**
     * The document-list port of the handover statement. The service that owns the documents binds it from Wave 3;
     * until then it is unbound and every statement names the list as unavailable (coordinator ruling INV-Q-4900).
     */
    @Bean
    @ConditionalOnMissingBean
    fun unsubmittedDocuments(): UnsubmittedDocuments = UnsubmittedDocuments.UNBOUND

    @Bean
    fun consentChangePublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
    ): ConsentChangePublisher {
        // wrapped, so that the bean is not also an AuthorityChangePublisher candidate of the grant use cases
        val events = AuthorityChangedOutbox(outbox, mapper)
        return ConsentChangePublisher { events.publish(it) }
    }

    @Bean
    fun consentDependencies(
        tx: TransactionRunner,
        consents: ConsentRepository,
        authorise: Authorise,
    ) = ConsentDependencies(tx, consents, authorise)

    @Bean
    fun consentEffects(
        publisher: ConsentChangePublisher,
        audit: GrantAudit,
    ) = ConsentEffects(publisher, audit)

    @Bean
    fun handoverPorts(
        documents: UnsubmittedDocuments,
        statements: HandoverStatements,
    ) = HandoverPorts(documents, statements)

    @Bean
    fun grantConsentUseCase(
        dependencies: ConsentDependencies,
        tenancy: AgentTenancy,
        effects: ConsentEffects,
    ) = GrantConsentUseCase(dependencies.tx, dependencies.consents, dependencies.authorise, tenancy, effects)

    @Bean
    fun withdrawConsentUseCase(
        dependencies: ConsentDependencies,
        handover: HandoverPorts,
        effects: ConsentEffects,
        clock: Clock,
    ) = WithdrawConsentUseCase(
        dependencies.tx,
        dependencies.consents,
        dependencies.authorise,
        handover,
        effects,
        clock,
    )
}

/** What both consent use cases share, so that no bean method takes more than a few parameters. */
class ConsentDependencies(
    val tx: TransactionRunner,
    val consents: ConsentRepository,
    val authorise: Authorise,
)
