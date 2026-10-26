package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.IdentityTripleCorrectedOutbox
import com.dynokore.gst.masterdata.adapters.events.MasterDataResolvedOutbox
import com.dynokore.gst.masterdata.adapters.events.MasterdataEventCatalogue
import com.dynokore.gst.masterdata.adapters.persistence.jooq.Masterdata
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.application.identity.BackdatedCorrections
import com.dynokore.gst.masterdata.application.identity.ConfirmerAuthority
import com.dynokore.gst.masterdata.application.identity.ConfirmerPolicy
import com.dynokore.gst.masterdata.application.identity.ConfirmerRoles
import com.dynokore.gst.masterdata.application.identity.IdentityTripleCorrectedPublisher
import com.dynokore.gst.masterdata.application.identity.TripleRepository
import com.dynokore.gst.masterdata.domain.identity.ConfirmerRole
import com.dynokore.gst.masterdata.domain.identity.ConfirmerSet
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.Schema
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/** The service's schema, its event catalogue and the defaults a deployment or a test replaces. */
@Configuration(proxyBeanMethods = false)
class ServiceDefaults {
    @Bean
    fun masterdataSchema(): Schema = Masterdata.MASTERDATA

    @Bean
    fun eventCatalogue(): EventCatalogue = MasterdataEventCatalogue.load()

    @Bean
    fun masterDataResolvedPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): MasterDataResolvedPublisher = MasterDataResolvedOutbox(outbox, mapper, tenants)

    @Bean
    fun identityTripleCorrectedPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): IdentityTripleCorrectedPublisher = IdentityTripleCorrectedOutbox(outbox, mapper, tenants)

    @Bean
    fun backdatedCorrections(
        triples: TripleRepository,
        clock: Clock,
        publisher: IdentityTripleCorrectedPublisher,
    ) = BackdatedCorrections(triples, clock, publisher)

    @Bean
    fun confirmerAuthority(
        policy: ConfirmerPolicy,
        roles: ConfirmerRoles,
    ) = ConfirmerAuthority(policy, roles)

    /**
     * The confirmer set. While VP-09 is `planned` the port holds exactly {entity.admin, tenant.admin, tax.approver}
     * (owner answer INV-Q-7880 A). The service adds no operation that sets it (ruling INV-Q-7888 A), and reading the
     * tenant's own value once VP-09 is available is not built here.
     */
    @Bean
    @ConditionalOnMissingBean
    fun confirmerPolicy(): ConfirmerPolicy = ConfirmerPolicy { PLANNED_CONFIRMERS }

    /**
     * The roles of the confirming subject at the entity come from service-runtime's [PolicyRoleResolver] asked at level
     * ENTITY with the entity as `scopeRef` and as `entityRef`. This service registers no resolver (the binding to the
     * authority replica is INV-T-8118). Fail closed (INV-Q-8121 A, CS-23, API-24): no resolver bean, an empty answer
     * and an exception alike give the empty set, so nobody can confirm.
     */
    @Bean
    @ConditionalOnMissingBean
    fun confirmerRoles(
        resolver: ObjectProvider<PolicyRoleResolver>,
        tenants: TenantContext,
    ): ConfirmerRoles =
        ConfirmerRoles { subject, entity ->
            val bound = resolver.ifAvailable
            if (bound == null) {
                emptySet()
            } else {
                try {
                    bound.rolesOf(subject.value, tenants.current(), ScopeLevel.ENTITY, entity.value, entity.value)
                } catch (_: Exception) {
                    emptySet()
                }
            }
        }

    private companion object {
        val PLANNED_CONFIRMERS =
            ConfirmerSet(setOf(ConfirmerRole.ENTITY_ADMIN, ConfirmerRole.TENANT_ADMIN, ConfirmerRole.TAX_APPROVER))
    }
}
