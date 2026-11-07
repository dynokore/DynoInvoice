package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.IdentityTripleChangedOutbox
import com.dynokore.gst.masterdata.adapters.events.IdentityTripleCorrectedOutbox
import com.dynokore.gst.masterdata.adapters.events.MasterDataResolvedOutbox
import com.dynokore.gst.masterdata.adapters.events.MasterdataEventCatalogue
import com.dynokore.gst.masterdata.adapters.permission.ReplicaConfirmerRoles
import com.dynokore.gst.masterdata.adapters.permission.ReplicaTenantPermission
import com.dynokore.gst.masterdata.adapters.persistence.jooq.Masterdata
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.application.identity.BackdatedCorrections
import com.dynokore.gst.masterdata.application.identity.ConfirmerAuthority
import com.dynokore.gst.masterdata.application.identity.ConfirmerPolicy
import com.dynokore.gst.masterdata.application.identity.ConfirmerRoles
import com.dynokore.gst.masterdata.application.identity.IdentityTripleChangedPublisher
import com.dynokore.gst.masterdata.application.identity.IdentityTripleCorrectedPublisher
import com.dynokore.gst.masterdata.application.identity.TripleRepository
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.domain.identity.ConfirmerRole
import com.dynokore.gst.masterdata.domain.identity.ConfirmerSet
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
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

    /** No default or no-op publisher exists: a missing bean fails the start (fail closed). */
    @Bean
    fun identityTripleChangedPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): IdentityTripleChangedPublisher = IdentityTripleChangedOutbox(outbox, mapper, tenants)

    @Bean
    fun backdatedCorrections(
        triples: TripleRepository,
        clock: Clock,
        publisher: IdentityTripleCorrectedPublisher,
        changedPublisher: IdentityTripleChangedPublisher,
    ) = BackdatedCorrections(triples, clock, publisher, changedPublisher)

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
     * The roles of the confirming subject at the entity come from the authority replica's public holder query
     * (service-runtime 12.0.0 removed `PolicyRoleResolver`). Fail closed (INV-Q-8121 A, CS-23, API-24): no replica, a
     * stale replica and an exception alike give the empty set, so nobody can confirm.
     */
    @Bean
    @ConditionalOnMissingBean
    fun confirmerRoles(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
        clock: Clock,
    ): ConfirmerRoles = ReplicaConfirmerRoles(replica.getIfAvailable(), tenants, clock)

    /** The permission check of the tenant-scoped operations: the authority replica, fail closed (INV-Q-17215 A). */
    @Bean
    @ConditionalOnMissingBean(TenantPermission::class)
    fun tenantPermission(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
    ): TenantPermission = ReplicaTenantPermission(replica.getIfAvailable(), tenants)

    private companion object {
        val PLANNED_CONFIRMERS =
            ConfirmerSet(setOf(ConfirmerRole.ENTITY_ADMIN, ConfirmerRole.TENANT_ADMIN, ConfirmerRole.TAX_APPROVER))
    }
}
