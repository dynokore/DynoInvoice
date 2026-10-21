package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessEventCatalogue
import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.adapters.http.LiveGrantManagerCheck
import com.dynokore.gst.access.adapters.http.LiveTenantGrants
import com.dynokore.gst.access.adapters.http.TenantPolicyRoleResolver
import com.dynokore.gst.access.adapters.persistence.jooq.Access
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Schema
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The service's schema, its event catalogue and the fail-closed defaults a deployment or a test replaces. */
@Configuration(proxyBeanMethods = false)
class ServiceDefaults {
    @Bean
    fun accessSchema(): Schema = Access.ACCESS

    @Bean
    fun eventCatalogue(): EventCatalogue = AccessEventCatalogue.load()

    /**
     * The role-by-permission table of the gst-catalogue for the installation's deployment form (INV-T-4935, INV-Q-7979
     * A). A role or permission key the loader cannot map stops the start-up (fail closed).
     */
    @Bean
    @ConditionalOnMissingBean
    fun permissionMatrix(properties: GstProperties): PermissionMatrix =
        PermissionMatrixLoader.load(catalogueForm(properties))

    /** The roles of the installation's form: a private installation has no `platform.support` (INV-REQ-888). */
    @Bean
    @ConditionalOnMissingBean
    fun roleCatalogue(properties: GstProperties): RoleCatalogue =
        PermissionMatrixLoader.roleCatalogue(catalogueForm(properties))

    private fun catalogueForm(properties: GstProperties): DeploymentForm = DeploymentForm.valueOf(properties.form.name)

    /**
     * The caller check of grantRole, revokeGrant and the sole-operator operations: a live Tenant-scope grant whose role
     * has `user.manage` PERMITTED in the matrix, read from this service's own grant table.
     */
    @Bean
    @ConditionalOnMissingBean
    fun grantManagerCheck(
        tenants: TenantContext,
        live: LiveTenantGrants,
        matrix: PermissionMatrix,
    ): GrantManagerCheck = LiveGrantManagerCheck(tenants, live, matrix)

    @Bean
    @ConditionalOnMissingBean
    fun liveTenantGrants(
        tenants: TenantContext,
        tx: TransactionRunner,
        grants: GrantRepository,
        clock: Clock,
    ) = LiveTenantGrants(tenants, tx, grants, clock)

    /** The roles `setPolicy` of service-runtime asks for the TENANT level (INV-REQ-2756 item 1). */
    @Bean
    @ConditionalOnMissingBean
    fun policyRoleResolver(live: LiveTenantGrants): PolicyRoleResolver = TenantPolicyRoleResolver(live)

    /**
     * Consent records belong to INV-T-4939; until then no consent is live, so a consent-derived grant covers nothing.
     */
    @Bean
    @ConditionalOnMissingBean
    fun consentCheck(): ConsentCheck = ConsentCheck { false }
}
