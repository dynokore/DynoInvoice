package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.persistence.JooqLatestDocumentVersions
import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.version.GetLatestDocumentVersion
import com.dynokore.gst.payload.application.version.LatestDocumentVersions
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The read of the latest document version (INV-REQ-500) composed: the store over the guarded [DSLContext] and the use
 * case over it and the replica-backed [OverrideAuthority]. The controller is a `@RestController` of payload-adapters.
 */
@Configuration(proxyBeanMethods = false)
class LatestVersionConfiguration {
    @Bean
    fun latestDocumentVersions(
        dsl: DSLContext,
        tenants: TenantContext,
    ): LatestDocumentVersions = JooqLatestDocumentVersions(dsl) { tenants.current().value }

    @Bean
    fun getLatestDocumentVersion(
        versions: LatestDocumentVersions,
        authority: OverrideAuthority,
    ): GetLatestDocumentVersion = GetLatestDocumentVersion(versions, authority)
}
