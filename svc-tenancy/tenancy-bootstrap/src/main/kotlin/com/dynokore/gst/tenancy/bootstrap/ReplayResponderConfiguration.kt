package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.JdbcReplayResponder
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.GstProperties
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The producer side of the replay protocol of tenancy-svc (INV-AC-25010, INV-Q-9081 A): the platform responder over the
 * request topics the catalogue lists for tenancy-svc, every [ReplayStateSource] bean (INV-T-9096) and the service's own
 * outbox writer (a dedicated [JdbcOutboxWriter], because the boundary tests wrap the shared [OutboxWriter] bean,
 * a final-class conflict for an injected [JdbcOutboxWriter]), with [ReplayResponderDriver] as the loop `EventLoops` runs.
 */
@Configuration(proxyBeanMethods = false)
class ReplayResponderConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun replayResponder(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        dsl: DSLContext,
        gst: GstProperties,
        sources: List<ReplayStateSource>,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcReplayResponder =
        JdbcReplayResponder(
            TenancyEventCatalogue.SERVICE,
            SCHEMA,
            catalogue,
            reader,
            scope,
            dsl,
            JdbcOutboxWriter(dsl, SCHEMA, gst.requireService(), gst.requireInstanceId(), catalogue, clock),
            sources,
            rebuildRequired,
            clock,
        )

    @Bean
    fun replayResponderDriver(responder: JdbcReplayResponder): ReplayResponderDriver =
        ReplayResponderDriver(responder::runOnce)

    companion object {
        const val SCHEMA = "tenancy"
    }
}
