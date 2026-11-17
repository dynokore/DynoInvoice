package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.consultant.CatalogueVp69Availability
import com.dynokore.gst.access.adapters.consultant.ConsultantGrantEndpoints
import com.dynokore.gst.access.adapters.consultant.JooqConsultantGrantRequests
import com.dynokore.gst.access.adapters.elevation.ElevationCommands
import com.dynokore.gst.access.application.consultant.ConsultantGrantRequests
import com.dynokore.gst.access.application.consultant.DecideConsultantGrantUseCase
import com.dynokore.gst.access.application.consultant.RequestConsultantGrantUseCase
import com.dynokore.gst.access.application.consultant.Vp69Availability
import com.dynokore.gst.access.application.elevation.ElevationCallers
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18: the consultant grant request and decision (INV-T-15800, INV-REQ-908, INV-REQ-2465) and their adapters. */
@Configuration(proxyBeanMethods = false)
class ConsultantGrantUseCases {
    @Bean
    fun consultantGrantRequests(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): ConsultantGrantRequests = JooqConsultantGrantRequests(dsl, tenants, clock)

    @Bean
    fun vp69Availability(catalogue: VariationPointCatalogue): Vp69Availability = CatalogueVp69Availability(catalogue)

    @Bean
    fun requestConsultantGrantUseCase(
        tx: TransactionRunner,
        requests: ConsultantGrantRequests,
        audit: GrantAudit,
    ) = RequestConsultantGrantUseCase(tx, requests, audit)

    @Bean
    @Suppress("LongParameterList") // one parameter per collaborator of the use case (TPL-18: the one place it is built)
    fun decideConsultantGrantUseCase(
        tx: TransactionRunner,
        requests: ConsultantGrantRequests,
        grantRole: GrantRoleUseCase,
        vp69: Vp69Availability,
        audit: GrantAudit,
        clock: Clock,
    ) = DecideConsultantGrantUseCase(tx, requests, grantRole, vp69, audit, clock)

    @Bean
    fun consultantGrantEndpoints(
        request: RequestConsultantGrantUseCase,
        decide: DecideConsultantGrantUseCase,
        callers: ElevationCallers,
        tenants: TenantContext,
        commands: ElevationCommands,
    ) = ConsultantGrantEndpoints(request, decide, callers, tenants, commands)
}
