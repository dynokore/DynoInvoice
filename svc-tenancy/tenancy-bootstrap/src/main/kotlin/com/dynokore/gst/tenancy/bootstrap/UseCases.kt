package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.OrganisationStructureChangedOutbox
import com.dynokore.gst.tenancy.adapters.persistence.JooqInstallationFactRepository
import com.dynokore.gst.tenancy.adapters.persistence.PlatformScope
import com.dynokore.gst.tenancy.application.installation.InstallationFactRepository
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * TPL-18: the only place an application or adapter object is constructed. No DataSource bean here or anywhere in the
 * service (owner ruling INV-Q-4196): the one application pool is Spring Boot's `dataSource` from spring.datasource.*.
 */
@Configuration(proxyBeanMethods = false)
class UseCases {
    @Bean
    fun platformScope(
        tenants: TenantContext,
        tx: TransactionRunner,
    ): PlatformScope = PlatformScope(tenants, tx, "tenancy-svc")

    @Bean
    fun installationFactRepository(
        dsl: DSLContext,
        scope: PlatformScope,
    ): InstallationFactRepository = JooqInstallationFactRepository(dsl, scope)

    @Bean
    fun structureChangePublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
    ): StructureChangePublisher = OrganisationStructureChangedOutbox(outbox, mapper)
}
