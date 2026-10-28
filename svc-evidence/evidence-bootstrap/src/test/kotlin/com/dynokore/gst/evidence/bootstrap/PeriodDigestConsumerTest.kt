package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.events.GstReturnRecordedHandler
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring.SCHEMA
import com.dynokore.gst.evidence.adapters.persistence.JooqChainTails
import com.dynokore.gst.evidence.adapters.persistence.JooqPeriodDigestStore
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreDigestObjects
import com.dynokore.gst.evidence.adapters.persistence.PeriodDigestObjectJson
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigest.Companion.PERIOD_DIGEST
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigestChain.Companion.PERIOD_DIGEST_CHAIN
import com.dynokore.gst.evidence.application.digest.DigestObjects
import com.dynokore.gst.evidence.application.digest.MaxRetention
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.application.digest.PeriodDigestRecorder
import com.dynokore.gst.evidence.application.digest.PeriodDigestStore
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
import software.amazon.awssdk.services.s3.model.NoSuchKeyException as S3NoSuchKey

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

    /** A look-alike of the SDK failure for an absent key: same simple name, another package (INV-Q-12051). */
    class NoSuchKeyException : RuntimeException("no such key")

    /** The seam beans of a context test: the ones a production installation would supply (INV-Q-12043, -12045). */
    private class Seams(
        val objectStore: ObjectStore? = null,
        val retention: MaxRetention? = null,
    )

    /** The failure injections of a test: the absent-key failure of the object store and a digest store wrapper. */
    private class Faults(
        val absent: () -> Throwable = { S3NoSuchKey.builder().message("no such key").build() },
        val wrapStore: (PeriodDigestStore) -> PeriodDigestStore = { it },
    )

    private class Objects(
        private val absent: () -> Throwable = { S3NoSuchKey.builder().message("no such key").build() },
    ) : ObjectStore {
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

        override fun get(key: ObjectKey): ByteArray = stored[key.value] ?: throw absent()
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
        seams: Seams? = null,
        faults: Faults = Faults(),
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val log = Log()
        val objects = (seams?.objectStore as? Objects) ?: Objects(faults.absent)
        val consumerName = LocatorConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, ReturnConsumerConfiguration.GST_RETURN_RECORDED_TOPIC, SCHEMA),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                if (seams == null) {
                    GstReturnRecordedHandler(
                        PeriodDigestRecorder(
                            JooqChainTails(dsl),
                            faults.wrapStore(JooqPeriodDigestStore(dsl)),
                            ObjectStoreDigestObjects(objects),
                            MaxRetention { RetentionPeriod(10) },
                        ),
                    )
                } else {
                    // the digest objects and the retention exactly as the service context wires them
                    ChainWiring.gstReturnRecordedHandler(
                        dsl,
                        context.getBean(DigestObjects::class.java),
                        context.getBean(MaxRetention::class.java),
                    )
                },
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
    }

    private fun run(
        dir: Path,
        version: EvidenceDb.Version,
        seams: Seams? = null,
        faults: Faults = Faults(),
        block: Boundary.(TenantId) -> Unit,
    ) {
        var runner = EvidenceContext.online(dir).withPropertyValues("spring.datasource.url=${version.jdbcUrl}")
        seams?.objectStore?.let { runner = runner.withBean(ObjectStore::class.java, { it }) }
        seams?.retention?.let { runner = runner.withBean(MaxRetention::class.java, { it }) }
        runner.run { context ->
            Boundary(context, seams, faults).block(TenantId(UUID.randomUUID()))
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
        val entry = expected(1, tails).copy(recordedAt = row.recordedAt!!.toInstant())
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
        objects.stored[key] = PeriodDigestObjectJson.bytes(tenant, expected(1, tails))
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

    private fun Boundary.pollFails(tenant: TenantId): Throwable {
        log.add(returnRecorded(tenant, "ret-1"))
        return runCatching { consumer.pollOnce() }.exceptionOrNull() ?: error("the poll was expected to fail")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `a get that fails with another package's NoSuchKeyException propagates and stores no digest`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version, faults = Faults(absent = { NoSuchKeyException() })) { tenant ->
        chains(tenant)
        pollFails(tenant)
        digests(tenant).size shouldBe 0
        chainRows(tenant).size shouldBe 0
        objects.stored.size shouldBe 0
        deadLetters(tenant) shouldBe 0
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `a get that fails with an IllegalStateException propagates and stores no digest`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version, faults = Faults(absent = { IllegalStateException("store down") })) { tenant ->
        chains(tenant)
        pollFails(tenant)
        digests(tenant).size shouldBe 0
        objects.stored.size shouldBe 0
        deadLetters(tenant) shouldBe 0
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `the SDK NoSuchKeyException on the cause chain means absent and a put follows`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(
        dir,
        version,
        faults =
            Faults(
                absent = { IllegalStateException("wrapped", S3NoSuchKey.builder().message("no such key").build()) },
            ),
    ) { tenant ->
        chains(tenant)
        log.add(returnRecorded(tenant, "ret-1"))
        consumer.pollOnce() shouldBe result(1, 0, 0)
        objects.stored.size shouldBe 1
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `with no MaxRetention bean the consumer retries, stores no digest and puts no object`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version, seams = Seams(objectStore = Objects())) { tenant ->
        chains(tenant)
        pollFails(tenant)
        digests(tenant).size shouldBe 0
        chainRows(tenant).size shouldBe 0
        objects.stored.size shouldBe 0
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `with a supplied MaxRetention bean of 10 years the retainUntil is recordedAt plus 10 years`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version, seams = Seams(Objects(), MaxRetention { RetentionPeriod(10) })) { tenant ->
        chains(tenant)
        log.add(returnRecorded(tenant, "ret-1"))
        consumer.pollOnce() shouldBe result(1, 0, 0)
        val recordedAt = digests(tenant).single().recordedAt!!.toInstant()
        objects.retention.values.single() shouldBe recordedAt.atZone(ZoneOffset.UTC).plusYears(10).toInstant()
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `with no ObjectStore bean the consumer retries and writes nothing`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version, seams = Seams(retention = MaxRetention { RetentionPeriod(10) })) { tenant ->
        chains(tenant)
        pollFails(tenant)
        digests(tenant).size shouldBe 0
        chainRows(tenant).size shouldBe 0
        objects.stored.size shouldBe 0
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `with a supplied ObjectStore bean the fallback backs off and that bean receives the put`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version, seams = Seams(Objects(), MaxRetention { RetentionPeriod(10) })) { tenant ->
        chains(tenant)
        log.add(returnRecorded(tenant, "ret-1"))
        consumer.pollOnce() shouldBe result(1, 0, 0)
        objects.stored.keys shouldBe setOf("period-digests/${tenant.value}/$entity/$period/1.json")
    }

    /** Every `period_digest` row has its three chain rows, and no chain row is without a digest (INV-Q-12053). */
    private fun Boundary.assertReferential(tenant: TenantId) {
        val rows = digests(tenant)
        val chainRows = chainRows(tenant)
        val types = setOf("PAYLOAD", "ACKNOWLEDGEMENT", "DECISION_TRACE")
        rows.forEach { d ->
            val mine =
                chainRows.filter {
                    it.tenantId == d.tenantId && it.entityRef == d.entityRef &&
                        it.periodRef == d.periodRef && it.digestSeq == d.digestSeq
                }
            mine.size shouldBe 3
            mine.map { it.evidenceType }.toSet() shouldBe types
        }
        val orphans =
            tx(tenant) {
                dsl
                    .select(PERIOD_DIGEST_CHAIN.EVIDENCE_TYPE)
                    .from(PERIOD_DIGEST_CHAIN)
                    .leftJoin(PERIOD_DIGEST)
                    .on(PERIOD_DIGEST_CHAIN.TENANT_ID.eq(PERIOD_DIGEST.TENANT_ID))
                    .and(PERIOD_DIGEST_CHAIN.ENTITY_REF.eq(PERIOD_DIGEST.ENTITY_REF))
                    .and(PERIOD_DIGEST_CHAIN.PERIOD_REF.eq(PERIOD_DIGEST.PERIOD_REF))
                    .and(PERIOD_DIGEST_CHAIN.DIGEST_SEQ.eq(PERIOD_DIGEST.DIGEST_SEQ))
                    .and(PERIOD_DIGEST.TENANT_ID.eq(tenant.value))
                    .where(PERIOD_DIGEST_CHAIN.TENANT_ID.eq(tenant.value))
                    .and(PERIOD_DIGEST.DIGEST.isNull)
                    .fetch()
                    .size
            }
        orphans shouldBe 0
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `every digest row has exactly its three chain rows after each consumed event`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) { tenant ->
        chains(tenant)
        for ((id, version2) in listOf("ret-1" to 1, "ret-2" to 2)) {
            log.add(returnRecorded(tenant, id, returnVersion = version2))
            consumer.pollOnce() shouldBe result(1, 0, 0)
            assertReferential(tenant)
        }
        digests(tenant).size shouldBe 2
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `when the insert of the third chain row fails neither table holds a row of the event`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version, faults = Faults(wrapStore = ::FailingOnThirdChainRow)) { tenant ->
        chains(tenant)
        pollFails(tenant)
        digests(tenant).size shouldBe 0
        chainRows(tenant).size shouldBe 0
        assertReferential(tenant)
    }

    /** Inserts the digest row and two chain rows, then offers the primary key of the second chain row again. */
    private class FailingOnThirdChainRow(
        private val real: PeriodDigestStore,
    ) : PeriodDigestStore by real {
        override fun insert(
            tenant: TenantId,
            entry: PeriodDigestEntry,
        ) = real.insert(tenant, entry.copy(chains = entry.chains.take(2) + entry.chains[1]))
    }
}
