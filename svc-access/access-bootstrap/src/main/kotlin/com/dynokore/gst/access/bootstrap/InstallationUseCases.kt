package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.InstallationFactOperations
import com.dynokore.gst.access.adapters.http.InstallationJournal
import com.dynokore.gst.access.adapters.persistence.JooqInstallationFacts
import com.dynokore.gst.access.adapters.persistence.JooqInstallationJournal
import com.dynokore.gst.access.application.approval.InstallationFacts
import com.dynokore.gst.access.application.approval.ReadInstallationFactUseCase
import com.dynokore.gst.access.application.approval.RecordInstallationFactUseCase
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18, continued: the installation approver fact (INV-REQ-992), constructed apart from [ApprovalUseCases]. */
@Configuration(proxyBeanMethods = false)
class InstallationUseCases {
    @Bean
    fun installationFacts(
        dsl: DSLContext,
        tenants: TenantContext,
    ): InstallationFacts = JooqInstallationFacts(dsl, tenants)

    @Bean
    fun recordInstallationFactUseCase(
        tx: TransactionRunner,
        facts: InstallationFacts,
        audit: GrantAudit,
        clock: Clock,
    ) = RecordInstallationFactUseCase(tx, facts, audit, clock)

    @Bean
    fun readInstallationFactUseCase(
        tx: TransactionRunner,
        facts: InstallationFacts,
    ) = ReadInstallationFactUseCase(tx, facts)

    /** The Idempotency-Key journal of the record, in its transaction (API-15, INV-Q-15370). */
    @Bean
    fun installationJournal(
        dsl: DSLContext,
        tx: TransactionRunner,
        facts: InstallationFacts,
    ): InstallationJournal = JooqInstallationJournal(dsl, tx, facts)

    @Suppress("LongParameterList") // one collaborator per port of the use case
    @Bean
    fun installationFactOperations(
        recordFact: RecordInstallationFactUseCase,
        readFact: ReadInstallationFactUseCase,
        serviceCaller: ServiceCaller,
        journal: InstallationJournal,
        platform: PlatformTenantBinding,
        tenants: TenantContext,
    ) = InstallationFactOperations(recordFact, readFact, serviceCaller, journal, platform, tenants)
}
