package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.OffboardingExportRequestedOutbox
import com.dynokore.gst.tenancy.adapters.persistence.JooqOffboardingStore
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.offboarding.CompleteOffboardingExport
import com.dynokore.gst.tenancy.application.offboarding.ExpireOverdueOffboarding
import com.dynokore.gst.tenancy.application.offboarding.OffboardTenant
import com.dynokore.gst.tenancy.application.offboarding.OffboardingExpiry
import com.dynokore.gst.tenancy.application.offboarding.OffboardingStore
import com.dynokore.gst.tenancy.application.offboarding.TenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration

/**
 * `tenancy.offboarding.export-timeout` (ISO-8601 duration, installation CONTROL-class setting, INV-REQ-877): how long a
 * REQUESTED export may wait for its result. The default is stated once, in `application.yaml` (12-factor III); an
 * unparsable, missing or non-positive value fails the start. Read once at start; no operation changes it at run time.
 */
@ConfigurationProperties(prefix = "tenancy.offboarding")
class OffboardingExportProperties(
    val exportTimeout: Duration?,
) {
    /** The validated limit. */
    fun limit(): Duration {
        val limit = checkNotNull(exportTimeout) { "tenancy.offboarding.export-timeout is not set" }
        check(!limit.isZero && !limit.isNegative) { "tenancy.offboarding.export-timeout must be positive" }
        return limit
    }
}

/**
 * TPL-18: offboardTenant (INV-T-16412 use cases) over the boundary adapters (INV-T-16413). The export port is the
 * transactional outbox publisher of `OffboardingExportRequested` (INV-AC-22961); the result arrives through
 * [OffboardingExportFinishedConsumerConfiguration]. The documents-count port is bound to the read model by
 * [OffboardingCountConfiguration]; a context without it refuses 422 POL-014.
 */
@Configuration(proxyBeanMethods = false)
@Import(OffboardingCountConfiguration::class)
@EnableConfigurationProperties(OffboardingExportProperties::class)
class OffboardingUseCases {
    @Bean
    fun offboardingStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqOffboardingStore = JooqOffboardingStore(dsl, tenants)

    /** The export is asynchronous: the request is appended to the outbox in the transaction of the record. */
    @Bean
    @ConditionalOnMissingBean(TenantExport::class)
    fun tenantExport(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): TenantExport = OffboardingExportRequestedOutbox(outbox, mapper, tenants)

    @Bean
    fun completeOffboardingExport(
        tx: TransactionRunner,
        store: JooqOffboardingStore,
        tenantStore: TenantStore,
    ): CompleteOffboardingExport = CompleteOffboardingExport(tx, store, tenantStore)

    /** Marks a REQUESTED record FAILED once it is older than the configured limit. */
    @Bean
    fun expireOverdueOffboarding(
        store: JooqOffboardingStore,
        clock: Clock,
        properties: OffboardingExportProperties,
    ): OffboardingExpiry = ExpireOverdueOffboarding(store, clock, properties.limit())

    /** The default when no read-model binding is loaded: the count is unreadable. */
    @Bean
    @ConditionalOnMissingBean(UnsubmittedDocuments::class)
    fun unsubmittedDocuments(): UnsubmittedDocuments =
        UnsubmittedDocuments {
            _,
            _,
            ->
            throw UnavailableInput(COUNT_INPUT)
        }

    @Bean
    @Suppress("LongParameterList")
    fun offboardTenant(
        tx: TransactionRunner,
        structure: StructureStore,
        store: OffboardingStore,
        documents: UnsubmittedDocuments,
        export: TenantExport,
        authority: ActionAuthority,
        tenants: TenantContext,
        tenantStore: TenantStore,
        expiry: OffboardingExpiry,
    ): OffboardTenant = OffboardTenant(tx, structure, store, documents, export, authority, tenants, tenantStore, expiry)

    private companion object {
        const val COUNT_INPUT = "unsubmitted-documents-count"
    }
}
