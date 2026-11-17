package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonAcknowledgementReceivedDataReader
import com.dynokore.gst.submission.adapters.events.SubmissionAcknowledgedOutbox
import com.dynokore.gst.submission.adapters.persistence.JooqAcknowledgementRecorder
import com.dynokore.gst.submission.adapters.persistence.JooqInFlightStore
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.application.queue.AcknowledgementReceivedHandler
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.platform.outboxandqueue.CausationId
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
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The acknowledgement writer over PostgreSQL as `submission_runtime`: the recorder, the existing store methods it is
 * used with and the outbox publisher, driven by the handler of INV-T-24274 (INV-AC-24271). The outbox is a recording
 * fake that sees the event in the same transaction; the topic is the catalogue's (wiring test).
 */
class AcknowledgementRecorderDbTest {
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
    private val outbox =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ) = events
                .map {
                    staged += it to context
                    null
                }.filterNotNull()
        }
    private val now = Instant.parse("2026-03-05T09:30:00Z")
    private val clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)

    private fun handler(recorder: com.dynokore.gst.submission.application.queue.AcknowledgementRecorder) =
        AcknowledgementReceivedHandler(
            tenants,
            JacksonAcknowledgementReceivedDataReader(),
            JooqInFlightStore(dsl),
            store,
            recorder,
            SubmissionAcknowledgedOutbox(outbox, tenants, store),
            clock,
        )

    private val handler = handler(JooqAcknowledgementRecorder(dsl))

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound = tenant
        SignedBindings.bind(connection, tenant)
        return block().also { connection.commit() }
    }

    private fun seed(
        tenant: UUID,
        doc: String,
        version: Int,
        state: String,
        ackId: String? = null,
    ) = asTenant(tenant) {
        store.insertReady(
            TenantId(tenant),
            NewReadySubmission(doc, version, "E1", null, byteArrayOf(1), "sha-$doc-$version", SubmissionKind.TYPE_1A),
        )
        dsl.execute(
            "update submission.submission set state = ?, acknowledgement_id = ?, " +
                "acknowledged_at = case when ?::text is null then null else now() end " +
                "where doc_uuid = ? and version = ?",
            state,
            ackId,
            ackId,
            doc,
            version,
        )
    }

    private fun row(
        tenant: UUID,
        doc: String,
        version: Int,
    ): Triple<String, String?, OffsetDateTime?> =
        asTenant(tenant) {
            val r =
                dsl.fetchOne(
                    "select state, acknowledgement_id, acknowledged_at from submission.submission " +
                        "where doc_uuid = ? and version = ?",
                    doc,
                    version,
                )!!
            Triple(r.get(0, String::class.java), r.get(1, String::class.java), r.get(2, OffsetDateTime::class.java))
        }

    private fun envelope(
        tenant: UUID,
        doc: String,
        version: Int,
        ack: String,
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
        dataJson =
            """{"adapter":"a","apNotificationId":"N-1","docUuid":"$doc","version":$version,""" +
                """"acknowledgementId":"$ack"}""",
    )

    private fun deliver(
        tenant: UUID,
        doc: String,
        version: Int,
        ack: String,
        with: AcknowledgementReceivedHandler = handler,
    ): HandleOutcome = asTenant(tenant) { with.handle(envelope(tenant, doc, version, ack)) }

    private fun schemaErrors(data: String): List<String> {
        // staged from the pinned platform-contracts bundle while submission-contract is configured
        val file =
            File(
                "../submission-contract/build/contracts-repo/.bundle/submission/schemas/" +
                    "submission-acknowledged-payload.schema.json",
            )
        check(file.isFile) { "the pinned payload schema is missing: $file" }
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return registry.getSchema(file.readText()).validate(data, InputFormat.JSON).map { it.toString() }
    }

    private fun halfWrites(doc: String): List<String> =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select doc_uuid from submission.ack_pair_probe where doc_uuid = '$doc'").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    @Test
    fun `INV-AC-24271 a SENT version is ACKNOWLEDGED with the id and instant written in one statement`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, 1, "SENT")
        deliver(t, doc, 1, "ACK-1") shouldBe HandleOutcome.APPLIED
        val (state, ack, at) = row(t, doc, 1)
        state shouldBe "ACKNOWLEDGED"
        ack shouldBe "ACK-1"
        at?.toInstant() shouldBe now
        // the deferred-pair probe: a trigger logs every UPDATE that leaves exactly one of the pair set
        halfWrites(doc).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-24271 a recorder that splits the write into two statements is caught by the pair probe`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, 1, "SENT")
        val splitting =
            com.dynokore.gst.submission.application.queue.AcknowledgementRecorder { tenant, d, v, id, at ->
                dsl.execute(
                    "update submission.submission set acknowledged_at = ?::timestamptz where tenant_id = ? " +
                        "and doc_uuid = ? " +
                        "and version = ?",
                    at.toString(),
                    tenant.value,
                    d,
                    v,
                )
                dsl.execute(
                    "update submission.submission set acknowledgement_id = ? where tenant_id = ? and doc_uuid = ? " +
                        "and version = ?",
                    id,
                    tenant.value,
                    d,
                    v,
                )
            }
        deliver(t, doc, 1, "ACK-1", handler(splitting)) shouldBe HandleOutcome.APPLIED
        halfWrites(doc).size shouldBe 1
    }

    @Test
    fun `INV-AC-24271 the repeat changes nothing and adds no event`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, 1, "SENT")
        deliver(t, doc, 1, "ACK-1") shouldBe HandleOutcome.APPLIED
        val first = row(t, doc, 1)
        staged.size shouldBe 1
        deliver(t, doc, 1, "ACK-1") shouldBe HandleOutcome.IGNORED
        row(t, doc, 1) shouldBe first
        staged.size shouldBe 1
    }

    @Test
    fun `INV-AC-24271 acknowledging version 2 supersedes version 1`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, 1, "ACKNOWLEDGED", "ACK-0")
        seed(t, doc, 2, "SENT")
        val before = row(t, doc, 1)
        deliver(t, doc, 2, "ACK-2") shouldBe HandleOutcome.APPLIED
        row(t, doc, 1).first shouldBe "SUPERSEDED"
        row(t, doc, 1).second shouldBe before.second
        row(t, doc, 1).third shouldBe before.third
        val v2 = row(t, doc, 2)
        v2.first shouldBe "ACKNOWLEDGED"
        v2.second shouldBe "ACK-2"
    }

    @Test
    fun `INV-AC-24271 one SubmissionAcknowledged event is staged whose data validates against the pinned schema`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, 1, "SENT")
        deliver(t, doc, 1, "ACK-1")
        staged.size shouldBe 1
        val (event, context) = staged.single()
        event.type shouldBe "com.dynokore.gst.submission.SubmissionAcknowledged.v1"
        context.tenant shouldBe TenantId(t)
        context.entity shouldBe EntityRef("E1")
        context.aggregateKey shouldBe doc
        val data = event.dataJson
        schemaErrors(data).shouldBeEmpty()
        val tree = ObjectMapper().readTree(data)
        tree.get("acknowledgementId").asString() shouldBe "ACK-1"
        tree.get("timestamp").asString() shouldBe "2026-03-05T09:30:00Z"
        // the schema is a real check: a payload without the timestamp is refused
        schemaErrors("""{"docUuid":"$doc","version":1,"acknowledgementId":"ACK-1"}""").isEmpty() shouldBe false
    }

    @Test
    fun `INV-AC-24271 the recorder requires one row and another tenant writes nothing`() {
        val t = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(t, doc, 1, "SENT")
        val recorder = JooqAcknowledgementRecorder(dsl)
        shouldThrow<IllegalStateException> {
            asTenant(t) { recorder.record(TenantId(t), doc, 9, "A", now) }
        }.message shouldBe "the locked submission row $doc version 9 was not updated"
        connection.rollback()
        val other = UUID.randomUUID()
        shouldThrow<IllegalStateException> {
            asTenant(other) { recorder.record(TenantId(other), doc, 1, "A", now) }
        }
        connection.rollback()
        row(t, doc, 1).second shouldBe null
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun installProbe() {
            SubmissionDb.superuser().use { c ->
                c.autoCommit = true
                c.createStatement().use {
                    it.execute("create table if not exists submission.ack_pair_probe (doc_uuid text)")
                    it.execute(
                        "create or replace function submission.ack_pair_probe_fn() returns trigger " +
                            "language plpgsql security definer as \$f\$ begin " +
                            "insert into submission.ack_pair_probe values (new.doc_uuid); return new; end \$f\$",
                    )
                    it.execute("drop trigger if exists ack_pair_probe_trg on submission.submission")
                    it.execute(
                        "create trigger ack_pair_probe_trg after update on submission.submission for each row " +
                            "when ((new.acknowledgement_id is null) <> (new.acknowledged_at is null)) " +
                            "execute function submission.ack_pair_probe_fn()",
                    )
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun removeProbe() {
            SubmissionDb.superuser().use { c ->
                c.autoCommit = true
                c.createStatement().use {
                    it.execute("drop trigger if exists ack_pair_probe_trg on submission.submission")
                    it.execute("drop table if exists submission.ack_pair_probe")
                    it.execute("drop function if exists submission.ack_pair_probe_fn()")
                }
            }
        }
    }
}
