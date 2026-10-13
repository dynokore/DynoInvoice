package com.dynokore.platform.serviceruntime.diagnostics

import com.dynokore.platform.serviceruntime.health.DomainDependencyHealthIndicator
import com.dynokore.platform.serviceruntime.health.OutboxDepthSource
import com.dynokore.platform.serviceruntime.health.QueueDepthSource
import com.dynokore.platform.serviceruntime.health.VaultHealthIndicator
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * Wires the per-code error counter and the uniform diagnostic endpoint
 * (FR-O.8, FR-AE.82, INV-AC-2172).
 */
@AutoConfiguration
class DiagnosticsAutoConfiguration {
    /** ARC-13: time from an injected `Clock`, never `Instant.now()`. */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean
    fun recentErrorCodesRecorder(clock: Clock): RecentErrorCodesRecorder = RecentErrorCodesRecorder(clock)

    @Bean
    @ConditionalOnMissingBean(ErrorCodeMetrics::class)
    fun errorCodeMetrics(
        meterRegistry: MeterRegistry,
        recorder: RecentErrorCodesRecorder,
    ): ErrorCodeMetrics = MicrometerErrorCodeMetrics(meterRegistry, recorder)

    @Bean
    @ConditionalOnMissingBean
    fun diagnosticsEndpoint(
        buildProperties: ObjectProvider<BuildProperties>,
        vaultHealthIndicator: VaultHealthIndicator,
        domainDependencyHealthIndicators: List<DomainDependencyHealthIndicator>,
        recorder: RecentErrorCodesRecorder,
        queueDepthSource: ObjectProvider<QueueDepthSource>,
        outboxDepthSource: ObjectProvider<OutboxDepthSource>,
    ): DiagnosticsEndpoint =
        DiagnosticsEndpoint(
            buildProperties,
            vaultHealthIndicator,
            domainDependencyHealthIndicators,
            recorder,
            queueDepthSource,
            outboxDepthSource,
        )
}
