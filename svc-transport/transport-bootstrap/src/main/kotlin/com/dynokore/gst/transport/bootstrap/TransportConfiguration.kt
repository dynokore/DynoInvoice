package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.ap.AccessPointRegistry
import com.dynokore.gst.transport.adapters.ap.ApEndpoints
import com.dynokore.gst.transport.adapters.ap.HttpAccessPointAdapter
import com.dynokore.gst.transport.adapters.metrics.AttemptMetrics
import com.dynokore.gst.transport.adapters.persistence.JooqApConnections
import com.dynokore.gst.transport.adapters.persistence.JooqAttemptLog
import com.dynokore.gst.transport.adapters.persistence.JooqEntityAdapterAssignments
import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.ApConnections
import com.dynokore.gst.transport.application.AttemptLog
import com.dynokore.gst.transport.application.EntityAdapterAssignments
import com.dynokore.gst.transport.application.SetTenantCeiling
import com.dynokore.gst.transport.application.SubmitPayload
import com.dynokore.gst.transport.application.SwitchEntityAdapter
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.binder.MeterBinder
import org.jooq.DSLContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * TPL-18: the use cases of the transport interface over the Access Point adapters and the jOOQ attempt log. The
 * wiring is here so `application` stays free of framework annotations (ARC-03).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApProperties::class)
class TransportConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun accessPointAdapters(properties: ApProperties): AccessPointAdapters {
        val endpoints = ApEndpoints(properties.environment, properties.sandboxEndpoint, properties.productionEndpoint)
        return AccessPointRegistry(
            properties.adapters.map {
                HttpAccessPointAdapter(AdapterId(it.id), endpoints, it.path, properties.timeout)
            },
        )
    }

    @Bean
    fun attemptLog(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
        platform: PlatformTenantBinding,
    ): AttemptLog = JooqAttemptLog(dsl, runner, tenants, platform, WORKLOAD_PRINCIPAL)

    @Bean
    fun entityAdapterAssignments(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
        clock: Clock,
    ): EntityAdapterAssignments = JooqEntityAdapterAssignments(dsl, runner, tenants, clock)

    @Bean
    fun apConnections(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ApConnections = JooqApConnections(dsl, runner)

    @Bean
    fun submitPayload(
        adapters: AccessPointAdapters,
        attempts: AttemptLog,
        assignments: EntityAdapterAssignments,
        clock: Clock,
    ): SubmitPayload = SubmitPayload(adapters, attempts, assignments, clock)

    @Bean
    fun setTenantCeiling(connections: ApConnections): SetTenantCeiling = SetTenantCeiling(connections)

    @Bean
    fun switchEntityAdapter(
        adapters: AccessPointAdapters,
        assignments: EntityAdapterAssignments,
    ): SwitchEntityAdapter = SwitchEntityAdapter(adapters, assignments)

    @Bean
    fun attemptMetrics(
        adapters: AccessPointAdapters,
        attempts: AttemptLog,
        properties: ApProperties,
        clock: Clock,
    ): MeterBinder = AttemptMetrics(adapters, attempts, properties.metricsWindow, clock)

    private companion object {
        const val WORKLOAD_PRINCIPAL = "svc:transport-svc"
    }
}
