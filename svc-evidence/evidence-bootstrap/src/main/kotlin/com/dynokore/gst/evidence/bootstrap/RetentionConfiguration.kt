package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.http.PartitionDeletionVisibility
import com.dynokore.gst.evidence.adapters.http.PlatformMaxRetention
import com.dynokore.gst.evidence.adapters.persistence.JooqPartitionDeletionOperations
import com.dynokore.gst.evidence.adapters.persistence.JooqPartitionLifecycle
import com.dynokore.gst.evidence.adapters.persistence.JooqRetentionRecords
import com.dynokore.gst.evidence.adapters.persistence.OutboxDeletionAudit
import com.dynokore.gst.evidence.adapters.retention.JsonTierYearsReader
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEnd
import com.dynokore.gst.evidence.application.retention.DeletionAudit
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
import com.dynokore.gst.evidence.application.retention.MaxRetention
import com.dynokore.gst.evidence.application.retention.PartitionLifecycle
import com.dynokore.gst.evidence.application.retention.RetentionPolicy
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.OptionValueMaximum
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import com.dynokore.gst.evidence.application.digest.MaxRetention as DigestMaxRetention

/**
 * `evidence.retention.*`: how long the status of a deletion is answered after it ended (API-17), an ISO-8601 duration,
 * default P7D, floor P7D (a lower value stops the start: [PartitionDeletionVisibility] refuses it).
 */
@ConfigurationProperties(prefix = "evidence.retention")
class RetentionProperties(
    val deletionStatusVisibility: Duration = PartitionDeletionVisibility.FLOOR,
    /** `archive-lock-period`: lock of an archive object; no default, unset refuses the deletion. */
    val archiveLockPeriod: Duration? = null,
)

/**
 * The retention use cases over config-resolver's ports, which service-runtime wires (TPL-18). The default years are
 * read from the VP-43 row of the catalogue, never written in code (INV-AC-10452).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetentionProperties::class)
class RetentionConfiguration {
    @Bean
    fun effectiveRetention(
        reader: PolicyReader,
        tenants: TenantContext,
        catalogue: VariationPointCatalogue,
    ): EffectiveRetention =
        EffectiveRetention(reader, tenants, RetentionPolicy.defaultYears(catalogue), JsonTierYearsReader())

    @Bean
    fun evidenceMaxRetentionFigure(
        maximum: OptionValueMaximum,
        catalogue: VariationPointCatalogue,
    ): MaxRetention = MaxRetention(maximum, RetentionPolicy.defaultYears(catalogue))

    @Bean
    fun platformMaxRetention(
        figure: MaxRetention,
        scope: TenantScope,
    ): PlatformMaxRetention = PlatformMaxRetention(figure, scope)

    /**
     * The port of the digest consumer (INV-T-7260): the same figure `getMaxEvidenceRetention` serves, in whole years
     * rounded up. Present, it makes the fallback of [ReturnConsumerConfiguration] back off.
     */
    @Bean
    @ConditionalOnMissingBean(DigestMaxRetention::class)
    fun evidenceDigestMaxRetention(platform: PlatformMaxRetention): DigestMaxRetention =
        DigestMaxRetention { platform.readIndependently { it.period() } }

    /** The readers of the deletion use case over the request's tenant-bound jOOQ context. */
    @Bean
    fun jooqRetentionRecords(
        dsl: DSLContext,
        effective: EffectiveRetention,
        clock: Clock,
    ): JooqRetentionRecords = JooqRetentionRecords(dsl, effective, clock)

    @Bean
    fun partitionDeletionOperations(dsl: DSLContext): JooqPartitionDeletionOperations =
        JooqPartitionDeletionOperations(dsl)

    @Bean
    fun deletionAudit(writer: AuditWriter): DeletionAudit = OutboxDeletionAudit(writer)

    @Bean
    fun partitionLifecycle(
        dsl: DSLContext,
        store: ObjectStore,
        clock: Clock,
        properties: RetentionProperties,
    ): PartitionLifecycle = JooqPartitionLifecycle(dsl, store, clock, properties.archiveLockPeriod)

    @Bean
    @Suppress("LongParameterList") // one port per fact of the ruled design
    fun deleteAtRetentionEnd(
        records: JooqRetentionRecords,
        audit: DeletionAudit,
        lifecycle: PartitionLifecycle,
        clock: Clock,
    ): DeleteAtRetentionEnd =
        DeleteAtRetentionEnd(records, records, records, records, records, records, audit, lifecycle, clock)

    /** `evidence.retention.deletion-status-visibility`: default P7D, floor P7D (a lower value stops the start). */
    @Bean
    fun partitionDeletionVisibility(properties: RetentionProperties): PartitionDeletionVisibility =
        PartitionDeletionVisibility(properties.deletionStatusVisibility)
}
