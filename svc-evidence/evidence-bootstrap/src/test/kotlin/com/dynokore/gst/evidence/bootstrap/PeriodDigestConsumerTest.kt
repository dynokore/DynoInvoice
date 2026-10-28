package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring.SCHEMA
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreDigestObjects
import com.dynokore.gst.evidence.adapters.persistence.PeriodDigestObjectJson
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigest.Companion.PERIOD_DIGEST
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigestChain.Companion.PERIOD_DIGEST_CHAIN
import com.dynokore.gst.evidence.application.digest.MaxRetention
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.digest.ChainTail
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.gst.evidence.domain.digest.periodDigest
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.RetentionPeriod
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
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-7255 (its first two steps: the event consumed, then delivered again) through the real inbox consumer of
 * `GstReturnRecorded`, with the handler and stores of the TPL-18 context over PostgreSQL (P109). Only the event-log
 * read and the object store are in memory.
 */
class PeriodDigestConsumerTest {
    private val actor = "svc:evidence-svc"
    private val entity = "ENT-3F2A9C"
    private val period = "2026-Q1"
    private val filedAt = Instant.parse("2026-04-15T08:00:00Z")
    private val json = EnvelopeJson()

    /** The failure the S3 SDK raises for an absent key; the adapter matches it by this simple name. */
    class NoSuchKeyException : RuntimeException("no such key")

    private class Objects : ObjectStore {
        val stored = mutableMapOf<String, ByteArray>()
        val retention = mutableMapOf<String, Instant>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            stored[key.value] = bytes
            retention[key.value] = retainUntil
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = stored[key.value] ?: throw NoSuchKeyException()
    }

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val log = Log()
        val objects = Objects()
        val consumerName = LocatorConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, ReturnConsumerConfiguration.GST_RETURN_RECORDED_TOPIC, SCHEMA),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                ChainWiring.gstReturnRecordedHandler(
                    dsl,
                    ObjectStoreDigestObjects(objects),
                    MaxRetention { RetentionPeriod(10) },
                ),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
    }

    private fun run(
        dir: Path,
        version: EvidenceDb.Version,
        block: Boundary.(TenantId) -> Unit,
    ) {
        EvidenceContext.online(dir).withPropertyValues("spring.datasource.url=${version.jdbcUrl}").run { context ->
            Boundary(context).block(TenantId(UUID.randomUUID()))
        }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    /** Appends [count] records to the chain table of [kind] for (entity, [forPeriod]). */
    private fun Boundary.fill(
        tenant: TenantId,
        kind: RecordKind,
        type: EvidenceType,
        count: Int,
        forPeriod: String = period,
    ) = tx(tenant) {
        val ledger = JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, kind.table)
        repeat(count) {
            ledger.append(tenant, EntityRef(entity), PeriodRef(forPeriod), type, """{"n":$it}""".toByteArray())
        }
    }

    private fun Boundary.chains(tenant: TenantId) {
        fill(tenant, RecordKind.EVIDENCE_PAYLOAD, EvidenceType.PAYLOAD, 3)
        fill(tenant, RecordKind.ACKNOWLEDGEMENT, EvidenceType.ACKNOWLEDGEMENT, 2)
        fill(tenant, RecordKind.DECISION_TRACE, EvidenceType.DECISION_TRACE, 3)
    }

    private fun Boundary.digests(tenant: TenantId) = tx(tenant) { dsl.fetch(PERIOD_DIGEST).sortedBy { it.digestSeq } }

    private fun Boundary.chainRows(tenant: TenantId) = tx(tenant) { dsl.fetch(PERIOD_DIGEST_CHAIN) }

    private fun Boundary.deadLetters(tenant: TenantId) =
        tx(tenant) { dsl.fetchCount(EVENT_DEAD_LETTER, EVENT_DEAD_LETTER.CONSUMER.eq(consumerName)) }

    private fun returnRecorded(
        tenant: TenantId,
        eventId: String,
        forPeriod: String = period,
        returnVersion: Int = 1,
        data: String? = null,
    ) = json.write(
        Envelope(
            id = EventId(eventId),
            source = "/gst/reconciliation-svc",
            type = "com.dynokore.gst.reconciliation.GstReturnRecorded.v1",
            subject = "$entity:$forPeriod",
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
                data
                    ?: """{"entityRef":"$entity","period":"$forPeriod","form":"GST F5",""" +
                    """"returnVersion":$returnVersion,""" +
                    """"filedAt":"$filedAt","irasFilingReference":"IRAS-1"}""",
        ),
    )

    private fun result(
        applied: Int,
        duplicates: Int,
        deadLettered: Int,
    ) = PollResult(applied = applied, ignored = 0, duplicates = duplicates, stale = 0, deadLettered = deadLettered)

    /** The entry the digest row of the given chains must hold, for the bytes of its object copy. */
    private fun expected(
        tenant: TenantId,
        sequence: Int,
        tails: List<ChainTail>,
    ) = PeriodDigestEntry(
        entity,
        period,
        sequence,
        1,
        filedAt,
        filedAt,
        "ret-1",
        tails,
        periodDigest(entity, period, 1, tails),
    )

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `INV-AC-7255 a filed return stores one period digest and its object copy, a redelivery stores nothing more`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) { tenant ->
        chains(tenant)
        val before = Instant.now()
        log.add(returnRecorded(tenant, "ret-1"))
        consumer.pollOnce() shouldBe result(1, 0, 0)

        val row = digests(tenant).single()
        row.digestSeq shouldBe 1
        row.returnVersion shouldBe 1
        row.filedAt?.toInstant() shouldBe filedAt
        row.sourceEventId shouldBe "ret-1"
        val rows = chainRows(tenant).associateBy { it.evidenceType }
        rows.keys shouldBe setOf("PAYLOAD", "ACKNOWLEDGEMENT", "DECISION_TRACE")
        DigestChainType.entries.map { rows.getValue(it.name).chainLength } shouldBe listOf(3L, 2L, 3L)
        rows.values.all { it.tailHash!!.matches(Regex("^[0-9a-f]{64}$")) } shouldBe true
        val tails =
            DigestChainType.entries.map {
                ChainTail(it, rows.getValue(it.name).chainLength!!, rows.getValue(it.name).tailHash)
            }
        row.digest shouldBe periodDigest(entity, period, 1, tails)
        row.digest!!.length shouldBe 71

        val key = "period-digests/${tenant.value}/$entity/$period/1.json"
        val entry = expected(tenant, 1, tails).copy(recordedAt = row.recordedAt!!.toInstant())
        objects.stored.keys shouldBe setOf(key)
        objects.stored.getValue(key).contentEquals(PeriodDigestObjectJson.bytes(tenant, entry)) shouldBe true
        val retainUntil = objects.retention.getValue(key)
        retainUntil shouldBe
            row.recordedAt!!
                .toInstant()
                .atZone(ZoneOffset.UTC)
                .plusYears(10)
                .toInstant()
        (row.recordedAt!!.toInstant() >= before) shouldBe true

        log.add(returnRecorded(tenant, "ret-1")) // redelivered
        consumer.pollOnce().duplicates shouldBe 1
        digests(tenant).size shouldBe 1
        chainRows(tenant).size shouldBe 3
        objects.stored.size shouldBe 1
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `a redelivery that finds the key with identical bytes continues`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) { tenant ->
        chains(tenant)
        // the object an earlier, rolled-back attempt left behind: the same bytes the digest of the chains gives
        val tails =
            listOf(
                tailOf(tenant, RecordKind.EVIDENCE_PAYLOAD, DigestChainType.PAYLOAD),
                tailOf(tenant, RecordKind.ACKNOWLEDGEMENT, DigestChainType.ACKNOWLEDGEMENT),
                tailOf(tenant, RecordKind.DECISION_TRACE, DigestChainType.DECISION_TRACE),
            )
        val key = "period-digests/${tenant.value}/$entity/$period/1.json"
        objects.stored[key] = PeriodDigestObjectJson.bytes(tenant, expected(tenant, 1, tails))
        log.add(returnRecorded(tenant, "ret-1"))
        consumer.pollOnce() shouldBe result(1, 0, 0)
        digests(tenant).single().digestSeq shouldBe 1
        objects.retention.size shouldBe 0 // nothing was put again
    }

    private fun Boundary.tailOf(
        tenant: TenantId,
        kind: RecordKind,
        type: DigestChainType,
    ): ChainTail {
        val last =
            tx(tenant) {
                JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, kind.table)
                    .findByEntityAndPeriod(tenant, EntityRef(entity), PeriodRef(period))
                    .last()
            }
        return ChainTail(type, last.sequence + 1, last.contentHash.hex)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `a key that holds other bytes sends the event to the dead letter and stores no digest`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) { tenant ->
        chains(tenant)
        objects.stored["period-digests/${tenant.value}/$entity/$period/1.json"] = "other".toByteArray()
        log.add(returnRecorded(tenant, "ret-1"))
        consumer.pollOnce() shouldBe result(0, 0, 1)
        deadLetters(tenant) shouldBe 1
        digests(tenant).size shouldBe 0
        chainRows(tenant).size shouldBe 0
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `a period that equals no chain stores a digest of empty chains, a later version a later digest`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) { tenant ->
        chains(tenant)
        log.add(returnRecorded(tenant, "ret-q2", forPeriod = "2026-Q2"))
        log.add(returnRecorded(tenant, "ret-1"))
        log.add(returnRecorded(tenant, "ret-2", returnVersion = 2))
        consumer.pollOnce() shouldBe result(3, 0, 0)
        val rows = tx(tenant) { dsl.fetch(PERIOD_DIGEST) }
        val empty = rows.single { it.periodRef == "2026-Q2" }
        empty.digestSeq shouldBe 1
        val emptyChains = chainRows(tenant).filter { it.periodRef == "2026-Q2" }
        emptyChains.map { it.chainLength } shouldBe listOf(0L, 0L, 0L)
        emptyChains.map { it.tailHash } shouldBe listOf("-", "-", "-")
        val q1 = rows.filter { it.periodRef == period }.sortedBy { it.digestSeq }
        q1.map { it.digestSeq } shouldBe listOf(1, 2)
        q1.map { it.returnVersion } shouldBe listOf(1, 2)
        objects.stored.size shouldBe 3
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `a payload without a return version goes to the dead letter`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) { tenant ->
        val noVersion = """{"entityRef":"$entity","period":"$period","filedAt":"$filedAt"}"""
        log.add(returnRecorded(tenant, "bad-1", data = noVersion))
        consumer.pollOnce() shouldBe result(0, 0, 1)
        digests(tenant).size shouldBe 0
    }
}
