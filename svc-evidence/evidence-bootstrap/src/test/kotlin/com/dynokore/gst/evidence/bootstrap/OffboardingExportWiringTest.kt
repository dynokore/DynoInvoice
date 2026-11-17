package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.events.OffboardingExportRequestedHandler
import com.dynokore.gst.evidence.adapters.persistence.JooqReconciliationWorkPapers
import com.dynokore.gst.evidence.adapters.persistence.JooqSourceDocumentReferences
import com.dynokore.gst.evidence.adapters.persistence.ObjectStorePackageStore
import com.dynokore.gst.evidence.application.export.ExportChainIntegrity
import com.dynokore.gst.evidence.application.export.ExportClassSource
import com.dynokore.gst.evidence.application.export.ExportClassSources
import com.dynokore.gst.evidence.application.export.ExportJobs
import com.dynokore.gst.evidence.application.export.ExportPackageStore
import com.dynokore.gst.evidence.application.export.ExportResultPublisher
import com.dynokore.gst.evidence.application.export.ExportTenantEvidence
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.RetentionPeriod
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.evidencestore.exportpackage.PackageItem
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.evidence.application.digest.MaxRetention as DigestMaxRetention
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-22949 through the real inbox consumer over PostgreSQL (P109): the handler, the use case, the job store and the
 * outbox publisher of [OffboardingExportConfiguration] in one consumer transaction, and the bindings of the
 * configuration over a context started as TPL-18 writes it. Only the event-log read is a list in memory.
 */
class OffboardingExportWiringTest {
    private val fixed = Instant.parse("2026-11-15T08:30:00Z")
    private val fixedClock: Clock = Clock.fixed(fixed, ZoneOffset.UTC)
    private val json = EnvelopeJson()
    private val mapper = JsonMapper.builder().build()
    private val configuration = OffboardingExportConfiguration()

    private companion object {
        const val TOPIC = OffboardingExportConfiguration.TOPIC
        const val SCHEME = OffboardingExportConfiguration.SCHEME_PROPERTY
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

    private fun candidate(
        tenant: TenantId,
        cls: PackageContentClass,
    ) = PackageCandidate(
        tenant,
        EntityRef("E1"),
        PeriodRef("2026-01"),
        PackageItem(cls, "${cls.directory}-1.txt", "${cls.name} 1".toByteArray()),
    )

    private fun sixSources(tenant: TenantId) =
        ExportClassSources(
            PackageContentClass.entries.associateWith { c -> ExportClassSource { listOf(candidate(tenant, c)) } },
        )

    private fun unboundSources(tenant: TenantId) =
        ExportClassSources(
            PackageContentClass.entries
                .filter { it != PackageContentClass.AUDIT_LOG }
                .associateWith { c -> ExportClassSource { listOf(candidate(tenant, c)) } },
        )

    private class Boundary(
        val context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
        val integrity: ExportChainIntegrity = context.getBean(ExportChainIntegrity::class.java)
        val log = Log()
        val consumerName = "offboarding-export-test-" + UUID.randomUUID()
    }

    private fun run(
        dir: Path,
        block: Boundary.() -> Unit,
    ) {
        EvidenceContext.online(dir).run { context -> Boundary(context).block() }
    }

    private fun Boundary.consumer(
        sources: ExportClassSources,
        failAfterPublish: Boolean = false,
    ): JdbcInboxConsumer {
        val real = configuration.offboardingExportResultPublisher(outbox, fixedClock)
        val publisher =
            if (failAfterPublish) {
                ExportResultPublisher {
                    real.publish(it)
                    error("unexpected failure after the outbox append")
                }
            } else {
                real
            }
        val store = ExportPackageStore { _, _, _ -> "s3://manifest" }
        val useCase =
            configuration.exportTenantEvidence(
                sources,
                configuration.offboardingExportJobs(dsl, fixedClock),
                store,
                integrity,
                publisher,
                fixedClock,
            )
        return JdbcInboxConsumer(
            ConsumerSubscription(consumerName, TOPIC, OffboardingExportConfiguration.SUBSCRIPTION.schema),
            context.getBean(EventCatalogue::class.java),
            log,
            scope,
            dsl,
            configuration.offboardingExportRequestedHandler(useCase),
            RebuildRequiredSink { },
            fixedClock,
        )
    }

    private fun request(
        tenant: TenantId,
        eventId: String,
        drop: String? = null,
    ): String {
        val members =
            linkedMapOf(
                "tenantRef" to tenant.toString(),
                "offboardingRef" to "OFF-1",
                "exportRef" to "EXP-1",
                "scope" to "TENANT",
                "requestedAt" to "2026-11-15T08:00:00Z",
            )
        drop?.let { members.remove(it) }
        val envelope =
            Envelope(
                id = EventId(eventId),
                source = "/gst/tenancy-svc",
                type = OffboardingExportConfiguration.EVENT_TYPE,
                subject = "OFF-1",
                time = fixed,
                tenant = tenant,
                entity = EventEntityRef("ENT-1"),
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "tenancy-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = members.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" },
            )
        return json.write(envelope)
    }

    private fun jobStates(tenant: TenantId): List<String> =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select state from evidence.offboarding_export_job where tenant_id = ?").use {
                it.setObject(1, tenant.value)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private class OutboxRow(
        val type: String,
        val entityRef: String,
        val data: String,
    )

    private fun outboxRows(tenant: TenantId): List<OutboxRow> =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select type, entity_ref, data::text from evidence.outbox where tenant_id = ?").use {
                it.setObject(1, tenant.value)
                it.executeQuery().use { rs ->
                    generateSequence {
                        if (rs.next()) OutboxRow(rs.getString(1), rs.getString(2), rs.getString(3)) else null
                    }.toList()
                }
            }
        }

    private fun Boundary.deadLetters() =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select count(*) from evidence.event_dead_letter where consumer = ?").use {
                it.setString(1, consumerName)
                it.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    /** The schema of the pinned bundle, read from the staged contract directory (`gst.contract.dir`). */
    private fun schemaErrors(data: String): List<String> {
        val file =
            File(System.getProperty("gst.contract.dir"))
                .resolve("schemas/offboarding-export-finished-payload.schema.json")
        check(file.isFile) { "schema file does not exist: $file" }
        return schemas.getSchema(file.readText()).validate(mapper.readTree(data)).map { it.toString() }
    }

    @Test
    fun `INV-AC-22949 the first delivery leaves one READY job and one outbox row, a repeat changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = TenantId(UUID.randomUUID())
        val consumer = consumer(sixSources(tenant))
        log.add(request(tenant, "ev-1"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        jobStates(tenant) shouldBe listOf("READY")
        val row = outboxRows(tenant).single()
        row.type shouldBe "com.dynokore.gst.evidence.OffboardingExportFinished.v1"
        row.entityRef shouldBe "00000000-0000-0000-0000-000000000000"
        val payload = mapper.readTree(row.data)
        payload.get("tenantRef").asString() shouldBe tenant.toString()
        payload.get("offboardingRef").asString() shouldBe "OFF-1"
        payload.get("exportRef").asString() shouldBe "EXP-1"
        payload.get("state").asString() shouldBe "READY"
        payload.get("finishedAt").asString() shouldBe fixed.toString()
        schemaErrors(row.data) shouldBe emptyList()

        log.add(request(tenant, "ev-1")) // the same envelope id delivered again
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0)
        jobStates(tenant) shouldBe listOf("READY")
        outboxRows(tenant).size shouldBe 1
    }

    @Test
    fun `INV-AC-22949 an envelope without offboardingRef is dead-lettered and no job exists`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = TenantId(UUID.randomUUID())
        val consumer = consumer(sixSources(tenant))
        log.add(request(tenant, "ev-2", drop = "offboardingRef"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        deadLetters() shouldBe 1
        jobStates(tenant) shouldBe emptyList()
        outboxRows(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 an unbound source leaves one FAILED outbox row whose reason names the class`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = TenantId(UUID.randomUUID())
        log.add(request(tenant, "ev-3"))
        consumer(unboundSources(tenant)).pollOnce().applied shouldBe 1
        jobStates(tenant) shouldBe listOf("FAILED")
        val payload = mapper.readTree(outboxRows(tenant).single().data)
        payload.get("state").asString() shouldBe "FAILED"
        payload.get("failureReason").asString() shouldContain "AUDIT_LOG"
        schemaErrors(payload.toString()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 an unexpected exception in the use case leaves neither a job row nor an outbox row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = TenantId(UUID.randomUUID())
        log.add(request(tenant, "ev-4"))
        runCatching { consumer(sixSources(tenant), failAfterPublish = true).pollOnce() }.isFailure shouldBe true
        jobStates(tenant) shouldBe emptyList()
        outboxRows(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 the context holds exactly one handler bean and the property of application yaml is s3`(
        @TempDir dir: Path,
    ) = run(dir) {
        // the runtime may wrap a handler bean in a tracing decorator, so the beans are counted by name
        val handlers = context.getBeansOfType(EventHandler::class.java).keys
        handlers.count { it == "offboardingExportRequestedHandler" } shouldBe 1
        context.environment.getProperty(OffboardingExportConfiguration.SCHEME_PROPERTY) shouldBe "s3"
        context.getBean(ExportPackageStore::class.java).shouldBeObjectStore()
    }

    private fun ExportPackageStore.shouldBeObjectStore() =
        assertThat(this).isInstanceOf(ObjectStorePackageStore::class.java)

    // ---- the bindings over test doubles: no database, no yaml ----

    private fun <T> stub(
        type: Class<T>,
        vararg more: Class<*>,
    ): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type, *more)) { _, method, _ ->
                throw UnsupportedOperationException("test double: ${method.name}")
            },
        )

    private class Recording : ObjectStore {
        val retention = mutableMapOf<String, Instant>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            retention[key.value] = retainUntil
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = error("not read")
    }

    private fun runner(recording: Recording): ApplicationContextRunner {
        val runner = stub(TransactionRunner::class.java, CurrentConnection::class.java)
        return ApplicationContextRunner()
            .withUserConfiguration(OffboardingExportConfiguration::class.java)
            .withBean(Clock::class.java, { fixedClock })
            .withBean(DSLContext::class.java, { DSL.using(SQLDialect.POSTGRES) })
            .withBean(OutboxWriter::class.java, { stub(OutboxWriter::class.java) })
            .withBean(ObjectStore::class.java, { recording })
            .withBean(DigestMaxRetention::class.java, { DigestMaxRetention { RetentionPeriod(9) } })
            .withBean(ExportChainIntegrity::class.java, { ExportChainIntegrity { true } })
            .withBean(EventCatalogue::class.java, { EventConfiguration().eventCatalogue() })
            .withBean(EventLogReader::class.java, { stub(EventLogReader::class.java) })
            .withBean(RebuildRequiredSink::class.java, { RebuildRequiredSink { } })
            .withBean(
                TenantScope::class.java,
                { TenantScope(stub(TenantContext::class.java), runner, runner as CurrentConnection, "evidence-svc") },
            )
    }

    private val files = PackageFiles(mapOf("manifest-sha256.txt" to "abc  data/x\n".toByteArray()))

    @Test
    fun `INV-AC-22949 the package store reference starts with the scheme of the property`() {
        runner(Recording()).withPropertyValues("$SCHEME=x-test").run { context ->
            val store = context.getBean(ExportPackageStore::class.java)
            assertThat(store).isInstanceOf(ObjectStorePackageStore::class.java)
            store.store(TenantId(UUID.randomUUID()), "OFF-1", files) shouldStartWith "x-test://"
        }
    }

    @Test
    fun `INV-AC-24168 the ExportClassSources bean binds every class, none is unbound`() {
        runner(Recording()).withPropertyValues("$SCHEME=s3").run { context ->
            assertThat(context.startupFailure).isNull()
            val sources = context.getBean(ExportClassSources::class.java)
            val unbound = PackageContentClass.entries.filter { sources.source(it) == null }
            unbound shouldBe emptyList()
            PackageContentClass.entries.size shouldBe 6
            assertThat(sources.source(PackageContentClass.SOURCE_DOCUMENT_REFERENCES))
                .isInstanceOf(JooqSourceDocumentReferences::class.java)
        }
    }

    @Test
    fun `INV-AC-24184 the context binds RECONCILIATION_WORK_PAPERS to its own source, the unbound set is empty`() {
        runner(Recording()).withPropertyValues("$SCHEME=s3").run { context ->
            assertThat(context.startupFailure).isNull()
            val sources = context.getBean(ExportClassSources::class.java)
            PackageContentClass.entries.filter { sources.source(it) == null }.toSet() shouldBe emptySet()
            assertThat(sources.source(PackageContentClass.RECONCILIATION_WORK_PAPERS))
                .isInstanceOf(JooqReconciliationWorkPapers::class.java)
        }
    }

    @Test
    fun `INV-AC-22950 the context holds exactly one ExportJobs bean and the use case holds that object`() {
        runner(Recording()).withPropertyValues("$SCHEME=s3").run { context ->
            assertThat(context.startupFailure).isNull()
            val beans = context.getBeansOfType(ExportJobs::class.java)
            beans.keys.toList() shouldBe listOf("offboardingExportJobs")
            val useCase = context.getBean(ExportTenantEvidence::class.java)
            val field = ExportTenantEvidence::class.java.getDeclaredField("jobs")
            field.isAccessible = true
            (field.get(useCase) === beans.getValue("offboardingExportJobs")) shouldBe true
        }
    }

    @Test
    fun `INV-AC-22949 the context fails to start without the scheme property, the failure names it`() {
        runner(Recording()).run { context ->
            assertThat(context.startupFailure).isNotNull
            val chain = generateSequence(context.startupFailure) { it.cause }
            assertThat(chain.joinToString { it.message.orEmpty() }).contains(SCHEME)
        }
    }

    @Test
    fun `INV-AC-22949 the retention is that of the DigestMaxRetention bean, nine years from the clock instant`() {
        val recording = Recording()
        runner(recording).withPropertyValues("$SCHEME=s3").run { context ->
            context.getBean(ExportPackageStore::class.java).store(TenantId(UUID.randomUUID()), "OFF-1", files)
            recording.retention.values.single() shouldBe Instant.parse("2035-11-15T08:30:00Z")
        }
    }

    @Test
    fun `INV-AC-22949 the context holds one handler bean and the consumer fails when the catalogue lacks the entry`() {
        runner(Recording()).withPropertyValues("$SCHEME=s3").run { context ->
            context.getBeansOfType(OffboardingExportRequestedHandler::class.java).size shouldBe 1
            context.getBeansOfType(JdbcInboxConsumer::class.java).size shouldBe 1
        }
        val without =
            EventCatalogue(
                EventConfiguration().eventCatalogue().entries().filterNot {
                    it.eventType == OffboardingExportConfiguration.EVENT_TYPE
                },
            )
        assertThatThrownBy {
            configuration.offboardingExportRequestedConsumer(
                without,
                stub(EventLogReader::class.java),
                TenantScope(
                    stub(TenantContext::class.java),
                    stub(TransactionRunner::class.java, CurrentConnection::class.java),
                    stub(TransactionRunner::class.java, CurrentConnection::class.java) as CurrentConnection,
                    "evidence-svc",
                ),
                RebuildRequiredSink { },
                fixedClock,
                DSL.using(SQLDialect.POSTGRES),
                configuration.offboardingExportRequestedHandler(
                    ExportTenantEvidence(
                        ExportClassSources(emptyMap()),
                        stub(ExportJobs::class.java),
                        { _, _, _ -> "ref" },
                        { true },
                        { },
                        fixedClock,
                    ),
                ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
