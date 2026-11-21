package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.OutboxReprocessingPagePublisher
import com.dynokore.gst.canonical.adapters.events.ReprocessingFunctions
import com.dynokore.gst.canonical.adapters.events.ReprocessingPagePublisher
import com.dynokore.gst.canonical.adapters.events.ReprocessingReads
import com.dynokore.gst.canonical.adapters.events.ReprocessingRequestInbox
import com.dynokore.gst.canonical.adapters.events.ReprocessingRequestMarker
import com.dynokore.gst.canonical.adapters.events.ReprocessingSpans
import com.dynokore.gst.canonical.adapters.events.SampleEntityIdentities
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityIdentities
import com.dynokore.gst.canonical.adapters.persistence.JooqPeriodSpans
import com.dynokore.gst.canonical.adapters.persistence.JooqReprocessingPopulation
import com.dynokore.gst.canonical.adapters.persistence.JooqReprocessingRequestMarkers
import com.dynokore.gst.canonical.application.sample.SampleDocumentFunctions
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import java.time.Clock

/**
 * INV-REQ-1003 (INV-T-24003): the subscription of canonical-svc to `PackReprocessingRequested` as one inbox consumer,
 * and the ports of the answer: the population and the acknowledgement replica are read locally (event-fed read
 * models, no synchronous call), and the pages go to the outbox. The page limit is `properties.documents.maxItems` of
 * the schema file `gst.reprocessing.recorded-schema-file` (the pinned bundle's
 * `pack-reprocessing-population-recorded-payload.schema.json`); it is read when the first request is answered and
 * never defaulted: a missing file or member fails the request (retried).
 */
@Configuration(proxyBeanMethods = false)
class ReprocessingConfiguration {
    @Bean
    fun reprocessingPagePublisher(
        outbox: OutboxWriter,
        tenants: TenantContext,
        trace: InboundEventTrace,
    ): ReprocessingPagePublisher =
        OutboxReprocessingPagePublisher(outbox, tenants, { trace.correlation() }, { trace.causation() })

    @Bean
    @Suppress("LongParameterList")
    fun reprocessingRequestHandler(
        dsl: DSLContext,
        clock: Clock,
        trace: InboundEventTrace,
        publisher: ReprocessingPagePublisher,
        functions: SampleDocumentFunctions,
        environment: Environment,
        loader: ResourceLoader,
    ): EventHandler {
        val identities = JooqEntityIdentities(dsl)
        val population = JooqReprocessingPopulation(dsl)
        val spans = JooqPeriodSpans(dsl)
        val markers = JooqReprocessingRequestMarkers(dsl, clock)
        val maxItems by lazy {
            SampleConfiguration.maxItems(environment.getProperty(SCHEMA_FILE_PROPERTY).orEmpty(), loader)
        }
        return InboundTraceHandler(
            ReprocessingRequestInbox(
                ReprocessingReads(
                    SampleEntityIdentities { tenant, ref -> identities.entityIdOf(tenant, ref) },
                    population,
                    population,
                    ReprocessingSpans { tenant, ref -> spans.spansOf(tenant, ref) },
                    ReprocessingFunctions { code -> functions.functionOf(code)?.name },
                ),
                ReprocessingRequestMarker { tenant, id -> markers.markAnswered(tenant, id) },
                publisher,
            ) { maxItems },
            trace,
        )
    }

    @Bean
    @Suppress("LongParameterList")
    fun reprocessingRequestConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        reprocessingRequestHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            reprocessingRequestHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc-pack-reprocessing-requested"
        const val TOPIC = "gst.platform.pack-reprocessing-requested.v1"
        const val SCHEMA = "canonical"
        const val SCHEMA_FILE_PROPERTY = "gst.reprocessing.recorded-schema-file"
    }
}
