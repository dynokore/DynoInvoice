package com.dynokore.gst.evidence.retention

import com.dynokore.gst.evidence.adapters.http.PlatformMaxRetention
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring.SCHEMA
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreDigestObjects
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigest.Companion.PERIOD_DIGEST
import com.dynokore.gst.evidence.application.retention.MaxRetention
import com.dynokore.gst.evidence.bootstrap.LocatorConsumerConfiguration
import com.dynokore.gst.evidence.bootstrap.ReturnConsumerConfiguration
import com.dynokore.platform.configresolver.OptionValueMaximum
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeoutException
import com.dynokore.gst.evidence.application.digest.MaxRetention as DigestMaxRetention
import com.dynokore.gst.evidence.domain.retention.RetentionTier.ACKNOWLEDGEMENTS_AND_TRACES as ACK
import com.dynokore.gst.evidence.domain.retention.RetentionTier.SOURCE_DOCUMENT_REFERENCES as SOURCE
import com.dynokore.gst.evidence.domain.retention.RetentionTier.STRUCTURED_SUBMISSIONS as STRUCTURED
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * The digest consumer with the production [DigestMaxRetention] bean of the service context (INV-Q-12043), and the
 * bound of its separate read (INV-Q-13964): the read waits at most the `x-timeout` of getMaxEvidenceRetention (2000 ms)
 * and fails closed.
 */
class DigestRetentionBeanTest {
    private val actor = "svc:evidence-svc"
    private val entity = "ENT-3F2A9C"
    private val period = "2026-Q1"
    private val filedAt = Instant.parse("2026-04-15T08:00:00Z")

    private class Objects : ObjectStore {
        val retention = mutableMapOf<String, Instant>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            retention[key.value] = retainUntil
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = error("no such key")
    }

    private class Log(
        private val records: List<LogRecord>,
    ) : EventLogReader {
        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private fun returnRecorded(tenant: TenantId) =
        EnvelopeJson().write(
            Envelope(
                id = EventId("ret-1"),
                source = "/gst/reconciliation-svc",
                type = "com.dynokore.gst.reconciliation.GstReturnRecorded.v1",
                subject = "$entity:$period",
                time = filedAt,
                tenant = tenant,
                entity = EventEntityRef(entity),
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "reconciliation-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"entityRef":"$entity","period":"$period","form":"GST F5","returnVersion":1,""" +
                        """"filedAt":"$filedAt","irasFilingReference":"IRAS-1"}""",
            ),
        )

    private fun RetentionService.consumer(
        tenant: TenantId,
        retention: DigestMaxRetention,
        objects: Objects,
    ): JdbcInboxConsumer {
        val dsl = bean(DSLContext::class.java)
        return JdbcInboxConsumer(
            ConsumerSubscription(
                LocatorConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID(),
                ReturnConsumerConfiguration.GST_RETURN_RECORDED_TOPIC,
                SCHEMA,
            ),
            bean(EventCatalogue::class.java),
            Log(listOf(LogRecord(1L, returnRecorded(tenant)))),
            bean(TenantScope::class.java),
            dsl,
            ChainWiring.gstReturnRecordedHandler(dsl, ObjectStoreDigestObjects(objects), retention),
            RebuildRequiredSink { },
            Clock.systemUTC(),
        )
    }

    private fun RetentionService.digests(tenant: TenantId) =
        bean(TenantScope::class.java).bind(tenant, actor) {
            bean(TransactionRunner::class.java).inTransaction { bean(DSLContext::class.java).fetch(PERIOD_DIGEST) }
        }

    @Test
    fun `the production bean with a seeded 10 locks the digest object until recordedAt plus 10 years`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            s.clearAll()
            val tenant = s.newTenant()
            val all = mapOf(STRUCTURED to 10, ACK to 10, SOURCE to 10)
            s.setEntity(tenant, "ENT-A", all, "obligation of the parent").accepted()
            val objects = Objects()
            val consumer = s.consumer(tenant, s.bean(DigestMaxRetention::class.java), objects)
            consumer.pollOnce().applied shouldBe 1
            val recordedAt = s.digests(tenant).single().recordedAt!!.toInstant()
            objects.retention.values.single() shouldBe recordedAt.atZone(ZoneOffset.UTC).plusYears(10).toInstant()
        }

    @Test
    fun `a read that never returns makes the bean throw within the bound and the consumer stores no digest`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            s.clearAll()
            val tenant = s.newTenant()
            val never = OptionValueMaximum { _, _ -> Thread.sleep(Long.MAX_VALUE).let { BigDecimal.ONE } }
            val platform = PlatformMaxRetention(MaxRetention(never, 5), s.bean(TenantScope::class.java))
            val bean = DigestMaxRetention { platform.readIndependently { it.period() } }

            val started = System.nanoTime()
            val failure = runCatching { bean.longest() }.exceptionOrNull()
            val took = Duration.ofNanos(System.nanoTime() - started)

            (failure is TimeoutException) shouldBe true
            (took >= PlatformMaxRetention.READ_TIMEOUT) shouldBe true
            (took < PlatformMaxRetention.READ_TIMEOUT.plusSeconds(MARGIN_SECONDS)) shouldBe true
            PlatformMaxRetention.READ_TIMEOUT shouldBe Duration.ofMillis(2000)

            val objects = Objects()
            val consumer = s.consumer(tenant, bean, objects)
            runCatching { consumer.pollOnce() }.isFailure shouldBe true
            s.digests(tenant).size shouldBe 0
            objects.retention.size shouldBe 0
        }

    private companion object {
        const val MARGIN_SECONDS = 3L
    }
}
