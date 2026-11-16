package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.alert.JooqActiveApprovers
import com.dynokore.gst.access.adapters.alert.JooqHeldApprover
import com.dynokore.gst.access.adapters.alert.JooqOrphanAlertState
import com.dynokore.gst.access.adapters.alert.JooqOrphanScanTenants
import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.gst.access.application.alert.ActiveApprovers
import com.dynokore.gst.access.application.alert.HeldApprover
import com.dynokore.gst.access.application.alert.OrphanAlertState
import com.dynokore.gst.access.application.alert.OrphanedApprovers
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * TPL-18, continued: ACC-001 for an entity left without an active `tax.approver` (INV-REQ-895). The real
 * [OrphanedApprovers] built here is the `orphans` argument of the grant and directory-group use cases, and the
 * scheduled scan settles the same check for every tenant.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrphanedApproverProperties::class)
class OrphanedApproverUseCases {
    @Bean
    fun activeApprovers(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
        consent: ConsentCheck,
        directory: EntityDirectory,
    ): ActiveApprovers = JooqActiveApprovers(dsl, tenants, clock, consent, directory)

    @Bean
    fun heldApprover(
        dsl: DSLContext,
        directory: EntityDirectory,
    ): HeldApprover = JooqHeldApprover(dsl, directory)

    @Bean
    fun orphanAlertState(
        dsl: DSLContext,
        clock: Clock,
    ): OrphanAlertState = JooqOrphanAlertState(dsl, clock)

    @Bean
    fun orphanedApprovers(
        active: ActiveApprovers,
        held: HeldApprover,
        state: OrphanAlertState,
        directory: EntityDirectory,
        alerts: AccessAlerts,
        clock: Clock,
    ) = OrphanedApprovers(active, held, state, directory, alerts, clock)

    @Bean
    fun orphanedApproverScan(
        dsl: DSLContext,
        tx: TransactionRunner,
        approvers: OrphanedApprovers,
        directory: EntityDirectory,
        tenants: TenantContext,
        platform: PlatformTenantBinding,
    ) = OrphanedApproverScan(JooqOrphanScanTenants(dsl, tx), approvers, directory, tenants, platform, tx)

    @Bean
    fun orphanedApproverScanLifecycle(
        scan: OrphanedApproverScan,
        properties: OrphanedApproverProperties,
    ) = OrphanedApproverScanLifecycle(scan, properties)
}
