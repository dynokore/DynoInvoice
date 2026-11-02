package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.elevation.JooqElevations
import com.dynokore.gst.access.application.authorise.OperatorOverlay
import com.dynokore.gst.access.application.authorise.PlatformRead
import com.dynokore.gst.access.application.elevation.DecideElevationUseCase
import com.dynokore.gst.access.application.elevation.ElevationCallers
import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.application.elevation.OpenElevationUseCase
import com.dynokore.gst.access.application.elevation.WithdrawElevationUseCase
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18: the elevation and diagnostic-invitation use cases (INV-T-4941) and the operator rules of `authorise`. */
@Configuration(proxyBeanMethods = false)
class ElevationUseCases {
    @Bean
    fun elevations(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): Elevations = JooqElevations(dsl, tenants, clock)

    @Bean
    fun operatorOverlay(
        elevations: Elevations,
        audit: GrantAudit,
        platform: PlatformRead,
        dependencies: GrantDependencies,
    ) = OperatorOverlay(elevations, audit, platform, dependencies.grants, dependencies.matrix)

    @Bean
    fun elevationCallers(
        platform: PlatformRead,
        dependencies: GrantDependencies,
        clock: Clock,
    ) = ElevationCallers(platform, dependencies.tx, dependencies.grants, dependencies.matrix, clock)

    @Bean
    fun openElevationUseCase(
        tx: TransactionRunner,
        elevations: Elevations,
        audit: GrantAudit,
    ) = OpenElevationUseCase(tx, elevations, audit)

    @Bean
    fun decideElevationUseCase(
        tx: TransactionRunner,
        elevations: Elevations,
        audit: GrantAudit,
        clock: Clock,
    ) = DecideElevationUseCase(tx, elevations, audit, clock)

    @Bean
    fun withdrawElevationUseCase(
        tx: TransactionRunner,
        elevations: Elevations,
        audit: GrantAudit,
    ) = WithdrawElevationUseCase(tx, elevations, audit)
}
