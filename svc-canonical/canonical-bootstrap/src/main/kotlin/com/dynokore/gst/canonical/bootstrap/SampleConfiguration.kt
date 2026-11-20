package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.OutboxSamplePagePublisher
import com.dynokore.gst.canonical.adapters.events.SampleAnswer
import com.dynokore.gst.canonical.adapters.events.SampleEntityIdentities
import com.dynokore.gst.canonical.adapters.events.SampleRequestInbox
import com.dynokore.gst.canonical.adapters.events.SampleRequestMarker
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityIdentities
import com.dynokore.gst.canonical.adapters.persistence.JooqSampleDocumentReader
import com.dynokore.gst.canonical.adapters.persistence.JooqSamplePeriodCalendar
import com.dynokore.gst.canonical.adapters.persistence.JooqSampleRequestMarkers
import com.dynokore.gst.canonical.adapters.persistence.JooqScopePackReplica
import com.dynokore.gst.canonical.application.sample.AnswerSampleRequest
import com.dynokore.gst.canonical.application.sample.SampleDocumentFunctions
import com.dynokore.gst.canonical.application.sample.SampleDocumentReader
import com.dynokore.gst.canonical.application.sample.SamplePagePublisher
import com.dynokore.gst.canonical.application.sample.SamplePeriodCalendar
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
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.Clock

/**
 * INV-REQ-772 b1 (slice 2, INV-T-4916): the subscription of canonical-svc to `PackDryRunSampleRequested` as one inbox
 * consumer, and the ports of the `AnswerSampleRequest` use case: the document reader, the period calendar and the
 * document functions are read locally (event-fed read models, no synchronous call), and the pages go to the outbox.
 * The page limit is `properties.documents.maxItems` of the schema file `gst.sample.recorded-schema-file`
 * (`service-runtime/schemas/pack-dry-run-sample-recorded-payload.schema.json` of the pinned bundle); it is read when
 * the first request is answered and never defaulted: a missing file or member fails the request (retried).
 */
@Configuration(proxyBeanMethods = false)
class SampleConfiguration {
    @Bean
    fun sampleDocumentFunctions(
        dsl: DSLContext,
        clock: Clock,
    ): SampleDocumentFunctions = JooqScopePackReplica(dsl, clock)

    @Bean
    fun samplePeriodCalendar(dsl: DSLContext): SamplePeriodCalendar = JooqSamplePeriodCalendar(dsl)

    @Bean
    fun sampleDocumentReader(dsl: DSLContext): SampleDocumentReader = JooqSampleDocumentReader(dsl)

    @Bean
    fun samplePagePublisher(
        outbox: OutboxWriter,
        tenants: TenantContext,
        trace: InboundEventTrace,
    ): SamplePagePublisher = OutboxSamplePagePublisher(outbox, tenants, { trace.correlation() }, { trace.causation() })

    @Bean
    @Suppress("LongParameterList")
    fun sampleRequestHandler(
        dsl: DSLContext,
        clock: Clock,
        trace: InboundEventTrace,
        reader: SampleDocumentReader,
        publisher: SamplePagePublisher,
        functions: SampleDocumentFunctions,
        calendar: SamplePeriodCalendar,
        environment: Environment,
    ): EventHandler {
        val identities = JooqEntityIdentities(dsl)
        val markers = JooqSampleRequestMarkers(dsl, clock)
        val answer by lazy {
            val schemaFile = environment.getProperty(SCHEMA_FILE_PROPERTY).orEmpty()
            AnswerSampleRequest(reader, publisher, functions, calendar, maxItems(schemaFile))
        }
        return InboundTraceHandler(
            SampleRequestInbox(
                SampleEntityIdentities { tenant, ref -> identities.entityIdOf(tenant, ref) },
                SampleRequestMarker { tenant, dryRunId -> markers.markAnswered(tenant, dryRunId) },
                SampleAnswer { answer.answer(it) },
            ),
            trace,
        )
    }

    @Bean
    @Suppress("LongParameterList")
    fun sampleRequestConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        sampleRequestHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            sampleRequestHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc-pack-dry-run-sample-requested"
        const val TOPIC = "gst.platform.pack-dry-run-sample-requested.v1"
        const val SCHEMA = "canonical"
        const val SCHEMA_FILE_PROPERTY = "gst.sample.recorded-schema-file"

        /** `properties.documents.maxItems` of the schema file; a blank path, a missing file or member is an error. */
        fun maxItems(schemaFile: String): Int {
            check(schemaFile.isNotBlank()) { "gst.sample.recorded-schema-file is not set" }
            val node =
                JsonMapper
                    .builder()
                    .build()
                    .readTree(File(schemaFile).readText())
                    .path("properties")
                    .path("documents")
                    .path("maxItems")
            val positive = node.isInt && node.asInt() >= 1
            check(positive) { "properties.documents.maxItems of $schemaFile is not a positive integer" }
            return node.asInt()
        }
    }
}
