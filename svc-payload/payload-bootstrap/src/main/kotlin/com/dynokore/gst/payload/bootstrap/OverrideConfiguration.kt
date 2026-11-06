package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.audit.OutboxOverrideAudit
import com.dynokore.gst.payload.adapters.clients.AccessReleaseDecisions
import com.dynokore.gst.payload.adapters.persistence.GstCodeCatalogue
import com.dynokore.gst.payload.adapters.persistence.JooqFindingsStore
import com.dynokore.gst.payload.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.adapters.persistence.StoredBlockingFindings
import com.dynokore.gst.payload.adapters.persistence.StoredDocumentVersions
import com.dynokore.gst.payload.application.override.ApproveOverride
import com.dynokore.gst.payload.application.override.AuditTrail
import com.dynokore.gst.payload.application.override.BlockingFindings
import com.dynokore.gst.payload.application.override.DocumentVersions
import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.override.OverrideStore
import com.dynokore.gst.payload.application.override.ReadOverrides
import com.dynokore.gst.payload.application.override.ReleaseDecisions
import com.dynokore.gst.payload.application.override.RequestOverride
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import java.time.Clock

/**
 * The validation override (INV-REQ-330, INV-REQ-500, INV-REQ-1293) composed: one bean per port and the three use cases
 * built from them (INV-Q-17266 A). The adapters are plain classes; this file is the only place they become beans.
 */
@Configuration(proxyBeanMethods = false)
class OverrideConfiguration {
    @Bean
    fun documentVersions(versionFactsStore: JooqVersionFactsStore): DocumentVersions =
        StoredDocumentVersions(versionFactsStore)

    @Bean
    fun blockingFindings(findingsStore: JooqFindingsStore): BlockingFindings =
        StoredBlockingFindings(findingsStore, GstCodeCatalogue())

    @Bean
    fun overrideStore(dsl: DSLContext): OverrideStore = JooqOverrideStore(dsl)

    @Bean
    fun overrideAudit(
        writer: AuditWriter,
        tenants: TenantContext,
        versions: DocumentVersions,
    ): AuditTrail = OutboxOverrideAudit(writer, tenants, versions)

    @Bean
    fun releaseDecisions(
        @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
        registry: ResilienceRegistry,
        clock: Clock,
        environment: Environment,
    ): ReleaseDecisions = AccessReleaseDecisions(builder, registry, clock, environment)

    @Bean
    fun overrideAuthority(
        replica: AuthorityReplica,
        tenants: TenantContext,
        clock: Clock,
    ): OverrideAuthority = ReplicaOverrideAuthority(replica, tenants, clock)

    @Bean
    @Suppress("LongParameterList")
    fun requestOverride(
        versions: DocumentVersions,
        authority: OverrideAuthority,
        findings: BlockingFindings,
        decisions: ReleaseDecisions,
        store: OverrideStore,
        audit: AuditTrail,
    ): RequestOverride = RequestOverride(versions, authority, findings, decisions, store, audit)

    @Bean
    fun approveOverride(
        versions: DocumentVersions,
        authority: OverrideAuthority,
        decisions: ReleaseDecisions,
        store: OverrideStore,
        audit: AuditTrail,
    ): ApproveOverride = ApproveOverride(versions, authority, decisions, store, audit)

    @Bean
    fun readOverrides(
        versions: DocumentVersions,
        authority: OverrideAuthority,
        store: OverrideStore,
    ): ReadOverrides = ReadOverrides(versions, authority, store)
}
