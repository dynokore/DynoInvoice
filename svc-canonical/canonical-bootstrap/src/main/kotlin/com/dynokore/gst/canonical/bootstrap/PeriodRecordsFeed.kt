package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.PeriodRecordsReader
import com.dynokore.gst.canonical.adapters.persistence.JooqPeriodSpans
import com.dynokore.gst.canonical.adapters.persistence.PeriodSpanSet
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The write side of the period span store; true when the set was newer and replaced the held one. */
fun interface PeriodSpanSink {
    fun apply(set: PeriodSpanSet): Boolean
}

/**
 * INV-AC-358, ruling INV-Q-21406 A: the subscription of canonical-svc to `PeriodRecordsChanged` as one inbox consumer.
 * The handler runs in the consumer's tenant transaction and applies the complete span set by the payload `version`
 * (version-guarded, idempotent). A malformed event is a contract violation (dead letter).
 */
@Configuration(proxyBeanMethods = false)
class PeriodRecordsFeed {
    @Bean
    fun periodRecordsHandler(dsl: DSLContext): EventHandler =
        PeriodRecordsHandler(PeriodRecordsReader(), PeriodSpanSink { JooqPeriodSpans(dsl).apply(it) })

    @Bean
    @Suppress("LongParameterList")
    fun periodRecordsConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        periodRecordsHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            periodRecordsHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc-period-records"
        const val TOPIC = "gst.tenancy.period-records-changed.v1"
        const val EVENT_TYPE = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
        const val SCHEMA = "canonical"
    }
}

/** Reads the span set and applies it with at most one store call; a stale version is ignored by the store. */
class PeriodRecordsHandler(
    private val reader: PeriodRecordsReader,
    private val store: PeriodSpanSink,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val set = reader.read(event.dataJson, event.tenant)
        return if (store.apply(set)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
