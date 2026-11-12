package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PeriodDueDate
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Delivery of the initial filing due date through the running consumer (INV-AC-20795). */
class PeriodRecordsInitialDueDateDbTest {
    private class ListReader(
        val records: MutableList<LogRecord> = mutableListOf(),
    ) : EventLogReader {
        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private val data =
        """{"entityRef":"E1","version":5,"periods":[
        {"periodRef":"2026-06","periodStart":"2026-06-01","periodEnd":"2026-06-30","filingDueDate":"2026-07-20"},
        {"periodRef":"2026-07","periodStart":"2026-07-01","periodEnd":"2026-07-31","filingDueDate":"2026-08-25"},
        {"periodRef":"2026-08","periodStart":"2026-08-01","periodEnd":"2026-08-31"},
        {"periodRef":"2026-09","periodStart":"2026-09-01","periodEnd":"2026-09-30","filingDueDate":"2026-10-20"}]}"""

    private fun envelope(tenant: UUID) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/tenancy-svc",
            type = SubmissionEvents.PERIOD_RECORDS_CHANGED_TYPE,
            subject = "E1",
            time = Instant.parse("2026-11-12T08:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("E1"),
            docUuid = DocUuid(UUID.randomUUID()),
            transactionDate = LocalDate.parse("2026-03-20"),
            docSequence = null,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun consumer(
        context: ApplicationContext,
        envelope: Envelope,
    ): JdbcInboxConsumer {
        val reader = ListReader()
        reader.records += LogRecord(1, EnvelopeJson().write(envelope))
        val name = "t20797-" + UUID.randomUUID()
        return JdbcInboxConsumer(
            ConsumerSubscription(name, SubmissionEvents.PERIOD_RECORDS_CHANGED_TOPIC, "submission"),
            context.getBean(EventCatalogue::class.java),
            reader,
            context.getBean(TenantScope::class.java),
            TenancyJooq.dslContext(
                OutboxSchema.jooqTables("submission"),
                context.getBean(TenantContext::class.java),
                context.getBean(TransactionRunner::class.java),
            ),
            context.getBean("periodRecordsChangedHandler", EventHandler::class.java),
            RebuildRequiredSink { },
        )
    }

    private fun row(
        context: ApplicationContext,
        tenant: UUID,
        period: String,
    ): String? {
        val t = PeriodDueDate.PERIOD_DUE_DATE
        return asTenant(context, tenant) {
            context
                .getBean(DSLContext::class.java)
                .selectFrom(t)
                .where(t.ENTITY_REF.eq("E1"))
                .and(t.PERIOD_REF.eq(period))
                .fetchOne()
                ?.let { "${it.filingDueDate}:${it.dueDateVersion}" }
        }
    }

    private fun <T> asTenant(
        context: ApplicationContext,
        tenant: UUID,
        block: () -> T,
    ): T =
        context.getBean(TenantContext::class.java).bind(TenantId(tenant), "svc:submission-svc") {
            context.getBean(TransactionRunner::class.java).inTransaction(block)
        }

    private fun seed(
        context: ApplicationContext,
        tenant: UUID,
        period: String,
        date: String,
        version: Long,
    ) {
        val dueDates = context.getBean(PeriodDueDates::class.java)
        asTenant(context, tenant) {
            dueDates.upsert(TenantId(tenant), "E1", period, LocalDate.parse(date), version)
        }
    }

    @Test
    fun `INV-AC-20795 the consumer stores the initial value but never overwrites a later one`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.online(dir).run { context ->
            val tenant = UUID.randomUUID()
            seed(context, tenant, "2026-07", "2026-08-20", 3)
            seed(context, tenant, "2026-06", "2026-07-22", 1)

            consumer(context, envelope(tenant)).pollOnce().applied shouldBe 1

            row(context, tenant, "2026-06") shouldBe "2026-07-22:1"
            row(context, tenant, "2026-07") shouldBe "2026-08-20:3"
            row(context, tenant, "2026-08") shouldBe null
            row(context, tenant, "2026-09") shouldBe "2026-10-20:0"
        }
    }
}
