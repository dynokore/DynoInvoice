package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AuthorityChangedPayloads
import com.dynokore.gst.access.adapters.events.InstallationRoleReconciler
import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.adapters.persistence.JooqInstallationRoleGrants
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18, continued: the configured `access.installation-roles` published as INSTALLATION role grants (INV-T-9235,
 * INV-Q-8972 A). Once the context has started, [InstallationRoleReconciler] writes the difference between the entries
 * and the rows of `installation_role_grant` and appends its AuthorityChanged events in one transaction bound to the
 * platform tenant; a start with unchanged entries writes nothing.
 */
@Configuration(proxyBeanMethods = false)
class InstallationRoleGrantsConfiguration {
    @Bean
    fun installationRoleGrants(dsl: DSLContext) = JooqInstallationRoleGrants(dsl)

    @Suppress("LongParameterList") // one collaborator per port of the reconciliation
    @Bean
    fun installationRoleReconciler(
        platform: PlatformTenantBinding,
        tx: TransactionRunner,
        grants: JooqInstallationRoleGrants,
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        clock: Clock,
        head: AuthorityHeadStore,
        incarnations: AuthorityIncarnationStore,
    ) = InstallationRoleReconciler(
        platform,
        tx,
        grants,
        outbox,
        AuthorityChangedPayloads(mapper, incarnations),
        clock,
        head,
    )

    /** Runs the reconciliation after every singleton exists, so that a refused entry stops the start. */
    @Bean
    fun installationRoleGrantsAtStart(
        reconciler: InstallationRoleReconciler,
        properties: InstallationRoleProperties,
    ) = SmartInitializingSingleton { reconciler.reconcile(properties.entries()) }
}
