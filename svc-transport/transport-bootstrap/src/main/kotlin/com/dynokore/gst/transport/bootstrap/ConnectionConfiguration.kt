package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.ap.AdapterCircuitBreaker
import com.dynokore.gst.transport.adapters.ap.ApConnectionProbe
import com.dynokore.gst.transport.adapters.ap.ApEndpoints
import com.dynokore.gst.transport.adapters.ap.BreakerSettings
import com.dynokore.gst.transport.adapters.events.ConnectionStatusEventRequester
import com.dynokore.gst.transport.adapters.persistence.JooqConnectionAssignments
import com.dynokore.gst.transport.adapters.persistence.JooqConnectionStatusStore
import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.ApProbe
import com.dynokore.gst.transport.application.ConnectionAssignments
import com.dynokore.gst.transport.application.ConnectionStatusEvents
import com.dynokore.gst.transport.application.ConnectionStatusStore
import com.dynokore.gst.transport.application.EntityRemovalGuard
import com.dynokore.gst.transport.application.FailoverAdapter
import com.dynokore.gst.transport.application.RouteChangeRecorder
import com.dynokore.gst.transport.application.SetConnectionEntities
import com.dynokore.gst.transport.application.SwitchEntityAdapter
import com.dynokore.gst.transport.application.TestConnection
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.connection.ConnectionStatusReporter
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * The thresholds of the adapter circuit breaker (INV-REQ-1353): configuration `transport.ap.breaker.*`, set in
 * application.yaml, never defaults in code. A missing value fails the start.
 */
@ConfigurationProperties("transport.ap.breaker")
data class BreakerProperties(
    val failureThreshold: Int,
    val openFor: Duration,
    val halfOpenSuccesses: Int,
)

/**
 * `failoverAdapter`, `setConnectionEntities` and `testConnection` (INV-REQ-796, INV-REQ-835, INV-REQ-836; INV-T-18675):
 * the use cases over the jOOQ stores, the SUB-018 lookup, the AP probe behind the adapter circuit breaker and the
 * shared `ConnectionStatusChanged` publisher of service-runtime.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BreakerProperties::class)
class ConnectionConfiguration {
    /**
     * The API-15 key store of `setConnectionEntities`, over the service's own schema (V3). The runtime declares the
     * same bean only when a job runtime or policy writer is present, so this one is declared here and the runtime's
     * backs off.
     */
    @Bean
    @ConditionalOnMissingBean
    fun idempotencyStore(
        scope: TenantScope,
        dsl: DSLContext,
    ): IdempotencyStore = IdempotencyStore(scope, dsl, RouteConfiguration.SCHEMA)

    @Bean
    fun adapterCircuitBreaker(
        properties: BreakerProperties,
        clock: Clock,
    ): AdapterCircuitBreaker =
        AdapterCircuitBreaker(
            BreakerSettings(properties.failureThreshold, properties.openFor, properties.halfOpenSuccesses),
            clock,
        )

    @Bean
    fun apProbe(
        adapters: AccessPointAdapters,
        breaker: AdapterCircuitBreaker,
    ): ApProbe = ApConnectionProbe(adapters, breaker)

    @Bean
    fun connectionStatusStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ConnectionStatusStore = JooqConnectionStatusStore(dsl, runner)

    @Bean
    fun connectionAssignments(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ConnectionAssignments = JooqConnectionAssignments(dsl, runner)

    @Bean
    fun connectionStatusEvents(
        reporter: ConnectionStatusReporter,
        properties: ApProperties,
        clock: Clock,
    ): ConnectionStatusEvents {
        val endpoints = ApEndpoints(properties.environment, properties.sandboxEndpoint, properties.productionEndpoint)
        val environment = properties.environment.name.lowercase()
        return ConnectionStatusEventRequester(
            reporter,
            { tenant, adapterId ->
                ConnectionStatusEventRequester.record(
                    tenant,
                    adapterId,
                    endpoints.active().toString(),
                    environment,
                    SecretRef("ap/$adapterId"),
                )
            },
            clock,
        )
    }

    @Bean
    fun testConnection(
        probe: ApProbe,
        breaker: AdapterCircuitBreaker,
        status: ConnectionStatusStore,
        events: ConnectionStatusEvents,
        clock: Clock,
    ): TestConnection = TestConnection(probe, breaker, status, events, clock)

    @Bean
    fun failoverAdapter(switch: SwitchEntityAdapter): FailoverAdapter = FailoverAdapter(switch)

    @Bean
    fun setConnectionEntities(
        recorder: RouteChangeRecorder,
        assignments: ConnectionAssignments,
        removal: EntityRemovalGuard,
    ): SetConnectionEntities = SetConnectionEntities(recorder, assignments, removal)
}
