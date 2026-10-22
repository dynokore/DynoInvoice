package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.PrincipalRevocationOutbox
import com.dynokore.gst.access.adapters.persistence.JooqPrincipalRepository
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.principal.CreatePrincipalUseCase
import com.dynokore.gst.access.application.principal.PrincipalRepository
import com.dynokore.gst.access.application.principal.PrincipalRevocationPublisher
import com.dynokore.gst.access.application.principal.RevokePrincipalUseCase
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/** TPL-18: the service principals' use cases (INV-T-4937), constructed here and nowhere else. */
@Configuration(proxyBeanMethods = false)
class PrincipalUseCases {
    @Bean
    fun principalRepository(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): PrincipalRepository = JooqPrincipalRepository(dsl, tenants, clock)

    @Bean
    fun principalRevocationPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        clock: Clock,
        directory: EntityDirectory,
    ): PrincipalRevocationPublisher = PrincipalRevocationOutbox(outbox, mapper, clock, directory)

    @Bean
    fun createPrincipalUseCase(
        tx: TransactionRunner,
        principals: PrincipalRepository,
        audit: GrantAudit,
    ) = CreatePrincipalUseCase(tx, principals, audit)

    @Bean
    fun revokePrincipalUseCase(
        tx: TransactionRunner,
        principals: PrincipalRepository,
        publisher: PrincipalRevocationPublisher,
        audit: GrantAudit,
    ) = RevokePrincipalUseCase(tx, principals, publisher, audit)
}
