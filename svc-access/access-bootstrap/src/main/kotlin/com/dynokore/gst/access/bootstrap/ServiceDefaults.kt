package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessEventCatalogue
import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.adapters.persistence.jooq.Access
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.outboxandqueue.EventCatalogue
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
     * Holds nothing until the permission catalogue is ruled (the names of the permissions of INV-NOTE-3 section 22.3
     * are
     * published nowhere): every `authorise` answers DENY and no event carries an action. Fail closed.
     */
    @Bean
    @ConditionalOnMissingBean
    fun permissionMatrix(): PermissionMatrix = PermissionMatrix.EMPTY

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
