package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.catalogue.GstErrorCatalogue
import com.dynokore.gst.submission.adapters.events.JacksonAcknowledgementOutcomeReader
import com.dynokore.gst.submission.adapters.events.JacksonAcknowledgementReceivedDataReader
import com.dynokore.gst.submission.adapters.events.JacksonRejectedAcknowledgementReader
import com.dynokore.gst.submission.adapters.events.OutboxSubmissionFailedPublisher
import com.dynokore.gst.submission.adapters.events.SubmissionAcknowledgedOutbox
import com.dynokore.gst.submission.adapters.persistence.JooqAcknowledgementRecorder
import com.dynokore.gst.submission.adapters.persistence.JooqFailureCodes
import com.dynokore.gst.submission.adapters.persistence.JooqInFlightStore
import com.dynokore.gst.submission.adapters.persistence.JooqLatestAttempt
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.application.queue.AcknowledgementOutcomeRouter
import com.dynokore.gst.submission.application.queue.AcknowledgementReceivedHandler
import com.dynokore.gst.submission.application.queue.FailureSignals
import com.dynokore.gst.submission.application.queue.LatestAttempt
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.application.queue.RejectedAcknowledgementHandler
import com.dynokore.gst.submission.application.queue.SubmissionFailure
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The REJECTED outcome of `AcknowledgementReceived` over PostgreSQL as `submission_runtime` (INV-AC-24283): the router,
 * the rejection handler of INV-T-24284 and the acknowledgement handler with the real adapters. The outbox is a fake
 * that writes a probe row in the same transaction and records the state of the version at that moment.
 */
class RejectedAcknowledgementDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val store = JooqSubmissionStore(dsl)
    private var bound: UUID = UUID.randomUUID()
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(bound)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val staged = mutableListOf<Pair<OutboundEvent, EventContext>>()
    private val stateWhenStaged = mutableListOf<String?>()
    private val outbox =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ) = events
                .map {
                    staged += it to context
                    stateWhenStaged +=
                        dsl
                            .fetchOne(
                                "select state from submission.submission where doc_uuid = ? and version = " +
                                    "(select max(version) from submission.submission where doc_uuid = ?)",
                                context.aggregateKey,
                                context.aggregateKey,
                            )?.get(0, String::class.java)
                    dsl.execute("insert into submission.reject_outbox_probe values (?)", context.aggregateKey)
                    null
                }.filterNotNull()
        }
    private val now = Instant.parse("2026-11-18T09:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val outcomeReader = JacksonAcknowledgementOutcomeReader()

    private fun router(
        signals: FailureSignals = OutboxSubmissionFailedPublisher(outbox, tenants, store),
        attempts: LatestAttempt = JooqLatestAttempt(dsl),
        codes: com.dynokore.gst.submission.application.queue.FailureCodes = JooqFailureCodes(dsl),
    ) = AcknowledgementOutcomeRouter(
        outcomeReader,
        accepted(),
        RejectedAcknowledgementHandler(
            tenants,
            JacksonRejectedAcknowledgementReader(),
            JooqInFlightStore(dsl),
            store,
            attempts,
            codes,
            GstErrorCatalogue(),
            signals,
        ),
    )

    private fun accepted() =
        AcknowledgementReceivedHandler(
            tenants,
            JacksonAcknowledgementReceivedDataReader(),
            JooqInFlightStore(dsl),
            store,
            JooqAcknowledgementRecorder(dsl),
            SubmissionAcknowledgedOutbox(outbox, tenants, store),
            clock,
        )

    private val router = router()

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound = tenant
        SignedBindings.bind(connection, tenant)
        return try {
            block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        }
    }

    private fun seed(
        tenant: UUID,
        doc: String,
        state: String,
        attempts: Int,
    ) = asTenant(tenant) {
        store.insertReady(
            TenantId(tenant),
            NewReadySubmission(doc, 1, "E1", null, byteArrayOf(1), "sha-$doc", SubmissionKind.TYPE_1A),
        )
        dsl.execute("update submission.submission set state = ? where doc_uuid = ? and version = 1", state, doc)
        (1..attempts).forEach { n ->
            dsl.execute(
                "insert into submission.submission_attempt (tenant_id, doc_uuid, version, attempt_number, " +
                    "correlation_id, requested_at) values (?, ?, 1, ?, ?, now())",
                tenant,
                doc,
                n,
                UUID.randomUUID(),
            )
        }
    }

    private fun stateOf(
        tenant: UUID,
        doc: String,
    ): String =
        asTenant(tenant) {
            dsl
                .fetchOne("select state from submission.submission where doc_uuid = ? and version = 1", doc)!!
                .get(0, String::class.java)
        }

    private fun classes(
        tenant: UUID,
        doc: String,
    ): List<String?> =
        asTenant(tenant) {
            dsl
                .fetch(
                    "select failure_class from submission.submission_attempt where doc_uuid = ? " +
                        "order by attempt_number",
                    doc,
                ).map { it.get(0, String::class.java) }
        }

    private fun probeRows(doc: String): Int =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select count(*) from submission.reject_outbox_probe where doc_uuid = '$doc'").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun envelope(
        tenant: UUID,
        doc: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/transport-svc",
        type = "com.dynokore.gst.transport.AcknowledgementReceived.v1",
        subject = doc,
        time = now,
        tenant = TenantId(tenant),
        entity = EntityRef("E1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "transport-svc-0",
        correlation = CorrelationId("c"),
        causation = CausationId("k"),
        dataJson = data,
    )

    private fun rejection(
        doc: String,
        reasons: String = """[{"code":"SUB-004-R1","message":"m1"}]""",
    ) = """{"docUuid":"$doc","version":1,"acknowledgementId":"A-1","outcome":"REJECTED","reasons":$reasons}"""

    private fun deliver(
        tenant: UUID,
        doc: String,
        data: String,
        with: AcknowledgementOutcomeRouter = router,
    ): HandleOutcome = asTenant(tenant) { with.handle(envelope(tenant, doc, data)) }

    private fun schemaErrors(data: String): List<String> {
        val file =
            File(
                "../submission-contract/build/contracts-repo/.bundle/submission/schemas/" +
                    "submission-failed-payload.schema.json",
            )
        check(file.isFile) { "the pinned payload schema is missing: $file" }
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return registry.getSchema(file.readText()).validate(data, InputFormat.JSON).map { it.toString() }
    }

    @Test
    fun `INV-AC-24283 a REJECTED event moves the version to REJECTED with one SubmissionFailed in the transaction`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, "SENT", 2)
        deliver(t, doc, rejection(doc)) shouldBe HandleOutcome.APPLIED
        stateOf(t, doc) shouldBe "REJECTED"
        staged.size shouldBe 1
        staged.single().first.type shouldBe "com.dynokore.gst.submission.SubmissionFailed.v1"
        // the state is already REJECTED when the outbox row is written (state first, then the row)
        stateWhenStaged shouldBe listOf("REJECTED")
        probeRows(doc) shouldBe 1
    }

    @Test
    fun `INV-AC-24283 the code is written on the latest attempt only`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, "SENT", 2)
        deliver(t, doc, rejection(doc))
        classes(t, doc) shouldBe listOf(null, "SUB-004-R1")
        asTenant(t) { JooqLatestAttempt(dsl).numberOf(TenantId(t), doc, 1) } shouldBe 2
        asTenant(t) { JooqLatestAttempt(dsl).numberOf(TenantId(t), doc, 7) } shouldBe null
        asTenant(UUID.randomUUID()) { JooqLatestAttempt(dsl).numberOf(TenantId(t), doc, 1) } shouldBe null
    }

    @Test
    fun `INV-AC-24283 the repeat and an acknowledged document change nothing and add no outbox row`() {
        val t = UUID.randomUUID()
        val d1 = UUID.randomUUID().toString()
        val d2 = UUID.randomUUID().toString()
        seed(t, d1, "SENT", 2)
        seed(t, d2, "ACKNOWLEDGED", 1)
        deliver(t, d1, rejection(d1)) shouldBe HandleOutcome.APPLIED
        deliver(t, d1, rejection(d1)) shouldBe HandleOutcome.IGNORED
        deliver(t, d2, rejection(d2)) shouldBe HandleOutcome.IGNORED
        staged.size shouldBe 1
        probeRows(d1) shouldBe 1
        probeRows(d2) shouldBe 0
        stateOf(t, d2) shouldBe "ACKNOWLEDGED"
        classes(t, d2) shouldBe listOf(null)
        classes(t, d1) shouldBe listOf(null, "SUB-004-R1")
    }

    @Test
    fun `INV-AC-24283 an ACCEPTED event and an event without outcome reach the acknowledgement handler`() {
        val t = UUID.randomUUID()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        seed(t, a, "SENT", 1)
        seed(t, b, "SENT", 1)
        deliver(t, a, """{"docUuid":"$a","version":1,"acknowledgementId":"A-1","outcome":"ACCEPTED"}""") shouldBe
            HandleOutcome.APPLIED
        deliver(t, b, """{"docUuid":"$b","version":1,"acknowledgementId":"A-2"}""") shouldBe HandleOutcome.APPLIED
        stateOf(t, a) shouldBe "ACKNOWLEDGED"
        stateOf(t, b) shouldBe "ACKNOWLEDGED"
        staged.map { it.first.type } shouldBe List(2) { "com.dynokore.gst.submission.SubmissionAcknowledged.v1" }
    }

    @Test
    fun `INV-AC-24283 an empty reasons list changes no row and the message is refused for the dead letter`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, "SENT", 2)
        shouldThrow<IllegalArgumentException> { deliver(t, doc, rejection(doc, "[]")) }
        stateOf(t, doc) shouldBe "SENT"
        classes(t, doc) shouldBe listOf(null, null)
        staged.shouldBeEmpty()
        shouldThrow<ContractViolation> { deliver(t, doc, rejection(doc).replace("REJECTED", "MAYBE")) }
        stateOf(t, doc) shouldBe "SENT"
    }

    @Test
    fun `INV-AC-24283 the SubmissionFailed data validates against the published schema`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, "SENT", 2)
        deliver(t, doc, rejection(doc))
        val data = staged.single().first.dataJson
        schemaErrors(data).shouldBeEmpty()
        val tree = ObjectMapper().readTree(data)
        tree.get("docUuid").asString() shouldBe doc
        tree.get("version").asInt() shouldBe 1
        tree.get("failureClass").asString() shouldBe "SUB-004-R1"
        // the schema is a real check: a payload without the version is refused
        schemaErrors("""{"docUuid":"$doc","failureClass":"SUB-004-R1"}""").isEmpty() shouldBe false
    }

    @Test
    fun `INV-AC-24283 a failure after the outbox row rolls the state, the code and the row back`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, "SENT", 2)
        val failing =
            router(
                signals =
                    FailureSignals { failure: SubmissionFailure ->
                        OutboxSubmissionFailedPublisher(outbox, tenants, store).stage(failure)
                        error("signal refused")
                    },
            )
        shouldThrow<IllegalStateException> { deliver(t, doc, rejection(doc), failing) }
        stateOf(t, doc) shouldBe "SENT"
        classes(t, doc) shouldBe listOf(null, null)
        probeRows(doc) shouldBe 0
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun installProbe() {
            SubmissionDb.superuser().use { c ->
                c.autoCommit = true
                c.createStatement().use {
                    it.execute("create table if not exists submission.reject_outbox_probe (doc_uuid text)")
                    it.execute("grant insert on submission.reject_outbox_probe to submission_runtime")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun removeProbe() {
            SubmissionDb.superuser().use { c ->
                c.autoCommit = true
                c.createStatement().use { it.execute("drop table if exists submission.reject_outbox_probe") }
            }
        }
    }
}
