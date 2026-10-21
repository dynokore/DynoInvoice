package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessEventCatalogue
import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.adapters.persistence.jooq.Access
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.serviceruntime.binding.GstProperties
import org.jooq.Schema
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
     * No grant manager is recognised until the permission catalogue is ruled (fail closed): grantRole and revokeGrant
     * answer 403.
     */
    @Bean
    @ConditionalOnMissingBean
    fun grantManagerCheck(): GrantManagerCheck = GrantManagerCheck.DENY_ALL

    /**
     * Consent records belong to INV-T-4939; until then no consent is live, so a consent-derived grant covers nothing.
     */
    @Bean
    @ConditionalOnMissingBean
    fun consentCheck(): ConsentCheck = ConsentCheck { false }
}
