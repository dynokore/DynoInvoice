package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.events.OutboxGstReturnRecordedPublisher
import com.dynokore.gst.reconciliation.adapters.http.ReturnsController
import com.dynokore.gst.reconciliation.adapters.persistence.JooqReturnRecords
import com.dynokore.gst.reconciliation.adapters.persistence.QueueRunTrigger
import com.dynokore.gst.reconciliation.application.returns.GstReturnRecordedPublisher
import com.dynokore.gst.reconciliation.application.returns.ReturnRecords
import com.dynokore.gst.reconciliation.application.returns.RunTrigger
import com.dynokore.gst.reconciliation.application.returns.StoredReturn
import com.dynokore.gst.reconciliation.application.returns.UploadGstReturn
import com.dynokore.gst.reconciliation.application.run.ReturnFiguresSource
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.GstReturnBox
import com.dynokore.gst.reconciliation.contract.model.GstReturnRecord
import com.dynokore.gst.reconciliation.contract.model.UploadGstReturnRequest
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * INV-AC-24366 at the database (INV-T-24367): an accepted return upload is stored append-only, publishes one
 * `GstReturnRecorded` event and queues one run, in one transaction. The use case runs over the real store, the real
 * outbox writer and the real work queue, in the real transaction runner of the tenancy kernel (TPL-18), over a real
 * PostgreSQL schema; the only stand-in is the source of the acting user and tenant, the `TenantContextImpl` the test
 * binds the way the request filter does.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class GstReturnUploadDbTest {
    private companion object {
        const val TYPE = "com.dynokore.gst.reconciliation.GstReturnRecorded.v1"
        const val TOPIC = "gst.reconciliation.gst-return-recorded.v1"
        const val FILED_AT = "2026-04-20T08:00:00Z"
        const val U1 = "user-U1"
        const val U2 = "user-U2"
        val mapper: JsonMapper = JsonMapper.builder().build()
        val clock: Clock = Clock.fixed(Instant.parse("2026-04-21T01:02:03Z"), ZoneOffset.UTC)
    }

    private class Session {
        val tenants = TenantContextImpl()
        private val source =
            PGSimpleDataSource().apply {
                setUrl(ReconciliationDb.jdbcUrl)
                user = ReconciliationDb.RUNTIME
                password = ReconciliationDb.RUNTIME_PASSWORD
            }
        val runner =
            TransactionRunnerImpl(
                source,
                tenants,
                null,
                object : BindingKeySource {
                    override fun currentKey(): ByteArray = ReconciliationDb.bindingKey.copyOf()

                    override fun previousKey(): ByteArray? = null
                },
            )

        private fun dsl(): DSLContext = DSL.using(runner.require(), SQLDialect.POSTGRES)

        private val scope = TenantScope(tenants, runner, runner, SignedBindings.ACTOR)
        private val catalogue =
            EventCatalogue(listOf(CatalogueEntry(TYPE, TOPIC, setOf("reconciliation-svc"), contentTopic = false)))

        // the real adapters, each built over the connection of the transaction open on the calling thread
        val records: ReturnRecords =
            object : ReturnRecords {
                override fun recorded(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                ) = JooqReturnRecords(dsl()).recorded(tenant, entityRef, periodRef)

                override fun insertIfAbsent(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                    stored: StoredReturn,
                ) = JooqReturnRecords(dsl()).insertIfAbsent(tenant, entityRef, periodRef, stored)
            }
        val figures =
            ReturnFiguresSource {
                tenant,
                entity,
                period,
                ->
                JooqReturnRecords(dsl()).current(tenant, entity, period)
            }
        private val publisher =
            GstReturnRecordedPublisher { tenant, entity, period, stored ->
                val outbox =
                    JdbcOutboxWriter(dsl(), "reconciliation", "reconciliation-svc", "test-instance", catalogue, clock)
                OutboxGstReturnRecordedPublisher(outbox).publish(tenant, entity, period, stored)
            }
        private val trigger =
            RunTrigger { tenant, entity, period, version ->
                val dsl = dsl()
                val limiter = LaneRateLimiter(scope, dsl, "reconciliation", LaneLimits())
                QueueRunTrigger(JdbcWorkQueue(scope, dsl, "reconciliation", limiter, clock))
                    .enqueue(tenant, entity, period, version)
            }
        val useCase = UploadGstReturn(records, publisher, trigger, clock)

        fun count(table: String) = dsl().fetchCount(DSL.table(DSL.name("reconciliation", table)))

        val controller = ReturnsController(useCase, runner, tenants)

        fun <T> asUser(
            tenant: UUID,
            user: String,
            block: () -> T,
        ): T = tenants.bind(TenantId(tenant), user, block)
    }

    private val session = Session()

    private fun request(
        version: Int,
        ack: String? = "ACK-$version",
        ref: String? = null,
        boxes: Map<String, String> = mapOf("1" to "100.00"),
    ) = UploadGstReturnRequest(
        version = version,
        formType = UploadGstReturnRequest.FormType.F5,
        filedAt = OffsetDateTime.parse(FILED_AT),
        boxes = boxes.map { GstReturnBox(it.key, BigDecimal(it.value)) },
        filingAcknowledgement = ack,
        filingReference = ref,
        preparer = "Preparer P",
    )

    private fun upload(
        tenant: UUID,
        user: String,
        request: UploadGstReturnRequest,
    ) = session.asUser(tenant, user) { session.controller.uploadGstReturn("E1", "P1", request) }

    private fun rows(
        sql: String,
        tenant: UUID,
    ): List<Map<String, Any?>> =
        ReconciliationDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                st.setObject(1, tenant)
                st.executeQuery().use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) })
                        }
                    }
                }
            }
        }

    private fun returns(tenant: UUID) =
        rows("select * from reconciliation.gst_return where tenant_id = ? order by version", tenant)

    private fun <T> inTx(
        tenant: UUID,
        user: String = U1,
        block: () -> T,
    ): T = session.asUser(tenant, user) { session.runner.inTransaction(block) }

    private fun events(tenant: UUID): List<JsonNode> =
        rows("select type, data::text as data from reconciliation.outbox where tenant_id = ? order by seq", tenant)
            .map {
                it["type"] shouldBe TYPE
                mapper.readTree(it["data"] as String)
            }

    private fun queued(tenant: UUID) =
        rows(
            "select partition_key, lane, payload::text as payload from reconciliation.work_queue where tenant_id = ?",
            tenant,
        )

    private fun sha256(text: String): String =
        "sha256:" +
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    /** Uploads: version 1 as U1, version 2 as U1, version 2 again as U2, then version 1 again as U1. */
    private fun scenario(tenant: UUID): List<ResponseEntity<GstReturnRecord>> =
        listOf(
            upload(tenant, U1, request(1, "ACK-1", "REF-1")),
            upload(tenant, U1, request(2)),
            upload(tenant, U2, request(2)),
            upload(tenant, U1, request(1, "ACK-1", "REF-1")),
        )

    @Test
    fun `INV-AC-24366 an accepted upload is stored with its boxes and a repeat answers the original`() {
        val t1 = UUID.randomUUID()
        val responses = scenario(t1)
        val first = responses[0]
        val second = responses[1]
        val repeat = responses[2]
        val again = responses[3]
        responses.map { it.statusCode.value() } shouldBe listOf(201, 201, 200, 200)
        (first.body as GstReturnRecord).filingReference shouldBe "REF-1"
        (repeat.body as GstReturnRecord) shouldBe (second.body as GstReturnRecord)
        (second.body as GstReturnRecord).filingReference shouldBe null
        (second.body as GstReturnRecord).preparer shouldBe "Preparer P"
        // version 1 is recorded: the domain order (a repeat before a lower version) makes it the original, 200 (GAP-1)
        (again.body as GstReturnRecord) shouldBe (first.body as GstReturnRecord)

        val stored = returns(t1)
        stored.map { it["version"] } shouldBe listOf(1, 2)
        stored.map { it["filing_reference"] } shouldBe listOf("REF-1", null)
        stored.map { it["preparer"] } shouldBe listOf("Preparer P", "Preparer P")
        stored.map { it["filing_acknowledgement"] } shouldBe listOf("ACK-1", "ACK-2")
        stored.map { (it["recorded_at"] as Timestamp).toInstant() } shouldBe List(2) { clock.instant() }
        stored.map { (it["filed_at"] as Timestamp).toInstant() } shouldBe List(2) { Instant.parse(FILED_AT) }
        stored.map { it["uploaded_by"] } shouldBe listOf(U1, U1)
        val boxes =
            rows(
                "select version, box, amount from reconciliation.gst_return_box where tenant_id = ? " +
                    "order by version, box",
                t1,
            )
        boxes.map { it["version"] to it["box"] } shouldBe listOf(1 to "1", 2 to "1")
        boxes.map { (it["amount"] as BigDecimal).toPlainString() } shouldBe listOf("100.00", "100.00")
    }

    @Test
    fun `INV-AC-24366 one GstReturnRecorded event per accepted version carries the contract types`() {
        val t1 = UUID.randomUUID()
        scenario(t1)
        val published = events(t1)
        published.size shouldBe 2
        published.map { it["returnVersion"].asString() } shouldBe listOf("1", "2")
        published.forEach {
            it["returnVersion"].isString shouldBe true
            it["entityRef"].asString() shouldBe "E1"
            it["period"].asString() shouldBe "P1"
            it["form"].asString() shouldBe "F5"
            it["filedAt"].asString() shouldBe FILED_AT
        }
        published[0]["acknowledgementHash"].asString() shouldBe sha256("ACK-1")
        Regex("^sha256:[0-9a-f]{64}$").matches(published[0]["acknowledgementHash"].asString()) shouldBe true
        published[1]["acknowledgementHash"].asString() shouldBe sha256("ACK-2")
        published[0]["irasFilingReference"].asString() shouldBe "REF-1"
        published[1].has("irasFilingReference") shouldBe false
        published[0].has("acknowledgement") shouldBe false
        val causes =
            rows("select causation_id, subject from reconciliation.outbox where tenant_id = ? order by seq", t1)
        causes.map { it["causation_id"] } shouldBe listOf("return-recorded:E1:P1:1", "return-recorded:E1:P1:2")
        causes.map { it["subject"] } shouldBe listOf("E1:P1", "E1:P1")
    }

    @Test
    fun `INV-AC-24366 the work queue holds one run entry per accepted version`() {
        val t1 = UUID.randomUUID()
        scenario(t1)
        val entries = queued(t1)
        entries.size shouldBe 2
        entries.forEach {
            it["partition_key"] shouldBe "E1:P1"
            it["lane"] shouldBe "LIVE"
        }
        entries.map { mapper.readTree(it["payload"] as String)["returnVersion"].asInt() }.sorted() shouldBe listOf(1, 2)
        mapper.readTree(entries[0]["payload"] as String).let {
            it["entityRef"].asString() shouldBe "E1"
            it["periodRef"].asString() shouldBe "P1"
        }
    }

    @Test
    fun `INV-AC-24366 a lower version than the recorded one answers 409 and adds no row, event or queue entry`() {
        val t1 = UUID.randomUUID()
        upload(t1, U1, request(2)).statusCode.value() shouldBe 201
        val lower = upload(t1, U1, request(1, "ACK-1", "REF-1"))
        lower.statusCode.value() shouldBe 409
        val envelope = lower.body as Any as ErrorEnvelope
        envelope.status shouldBe 409
        lower.headers.contentType.toString() shouldBe "application/problem+json"
        returns(t1).map { it["version"] } shouldBe listOf(2)
        events(t1).size shouldBe 1
        queued(t1).size shouldBe 1
    }

    @Test
    fun `INV-AC-24366 a blank filing reference is not published and the acknowledgement is never the reference`() {
        val t1 = UUID.randomUUID()
        upload(t1, U1, request(1, "ACK-1", "   ")).statusCode.value() shouldBe 201
        val published = events(t1)
        published.size shouldBe 1
        published[0].has("irasFilingReference") shouldBe false
        published[0].toString().contains("ACK-1") shouldBe false
    }

    @Test
    fun `INV-AC-24366 a missing acknowledgement answers 422 PER-014 and records nothing`() {
        val t1 = UUID.randomUUID()
        val refused = upload(t1, U1, request(1, ack = null))
        refused.statusCode.value() shouldBe 422
        (refused.body as Any as ErrorEnvelope).code shouldBe "PER-014"
        returns(t1).size shouldBe 0
        events(t1).size shouldBe 0
        queued(t1).size shouldBe 0
    }

    @Test
    fun `INV-AC-24366 the figures source reads the boxes of the highest stored version of its tenant only`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        upload(t1, U1, request(1, boxes = mapOf("1" to "100.00")))
        upload(t1, U1, request(2, boxes = mapOf("1" to "110.00", "2" to "5.50")))
        val current = inTx(t1) { session.figures.current(t1.toString(), "E1", "P1") }
        current?.version shouldBe 2
        current?.boxes?.mapValues { it.value.toPlainString() } shouldBe mapOf("1" to "110.00", "2" to "5.50")
        inTx(t1) { session.figures.current(t1.toString(), "E1", "P2") } shouldBe null
        // T2 reads no row of T1, by the row-level security and by the tenant predicate of the statement
        inTx(t2) { session.figures.current(t1.toString(), "E1", "P1") } shouldBe null
        inTx(t2) { session.records.recorded(t2.toString(), "E1", "P1") } shouldBe emptyList()
        inTx(t2) { session.count("gst_return") } shouldBe 0
        // the predicate alone keeps the row from another tenant when the policy is bypassed (database owner)
        ReconciliationDb.superuser().use { c ->
            JooqReturnRecords(DSL.using(c, SQLDialect.POSTGRES)).recorded(t2.toString(), "E1", "P1")
        } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24366 a stored return row is never updated or deleted, an empty uploaded_by is refused`() {
        val t1 = UUID.randomUUID()
        upload(t1, U1, request(1, "ACK-1", "REF-1"))

        fun refused(sql: String): String? =
            ReconciliationDb.connect(ReconciliationDb.RUNTIME, ReconciliationDb.RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                SignedBindings.bind(c, t1)
                try {
                    c.prepareStatement(sql).use { it.executeUpdate() }
                    null
                } catch (e: SQLException) {
                    e.sqlState
                } finally {
                    c.rollback()
                }
            }
        // the runtime login holds no update or delete privilege: 42501 insufficient_privilege
        refused("update reconciliation.gst_return set preparer = 'X'") shouldBe "42501"
        refused("delete from reconciliation.gst_return") shouldBe "42501"
        refused("update reconciliation.gst_return_box set amount = 1") shouldBe "42501"

        // the trigger refuses the same statements for the owner, with the same state
        fun ownerRefused(sql: String): String? =
            ReconciliationDb.superuser().use { c ->
                try {
                    c.prepareStatement(sql).use { it.executeUpdate() }
                    null
                } catch (e: SQLException) {
                    e.sqlState
                }
            }
        ownerRefused("update reconciliation.gst_return set preparer = 'X' where tenant_id = '$t1'") shouldBe "42501"
        ownerRefused("delete from reconciliation.gst_return where tenant_id = '$t1'") shouldBe "42501"
        ownerRefused("update reconciliation.gst_return_box set amount = 1 where tenant_id = '$t1'") shouldBe "42501"
        ownerRefused("delete from reconciliation.gst_return_box where tenant_id = '$t1'") shouldBe "42501"
        returns(t1).single()["preparer"] shouldBe "Preparer P"

        for (empty in listOf("''", "'   '")) {
            refused(
                "insert into reconciliation.gst_return (tenant_id, entity_ref, period_ref, version, form, filed_at, " +
                    "filing_acknowledgement, uploaded_by, recorded_at) " +
                    "values ('$t1', 'E1', 'P9', 1, 'F5', now(), 'A', $empty, now())",
            ) shouldBe "23514"
        }
        returns(t1).size shouldBe 1
    }

    @Test
    fun `INV-AC-24366 a rollback after the publish leaves no row, no event and no queue entry`() {
        val t1 = UUID.randomUUID()
        shouldThrow<IllegalStateException> {
            session.asUser(t1, U1) {
                session.runner.inTransaction {
                    session.useCase.execute(
                        t1.toString(),
                        "E1",
                        "P1",
                        com.dynokore.gst.reconciliation.domain.returns.ReturnUpload(
                            1,
                            com.dynokore.gst.reconciliation.domain.returns.ReturnForm.F5,
                            Instant.parse(FILED_AT),
                            "ACK-1",
                            null,
                        ),
                        mapOf("1" to BigDecimal("100.00")),
                        "REF-1",
                        null,
                        U1,
                    )
                    // the store, the event and the queue entry are written: the failure comes after the publish
                    check(session.count("outbox") == 1)
                    error("forced failure after the publish")
                }
            }
        }
        returns(t1).size shouldBe 0
        events(t1).size shouldBe 0
        queued(t1).size shouldBe 0
        rows("select 1 as x from reconciliation.gst_return_box where tenant_id = ?", t1).size shouldBe 0
    }

    @Test
    fun `INV-AC-24366 the acting user is stored as uploaded_by and a repeat by another user keeps it`() {
        val t1 = UUID.randomUUID()
        upload(t1, U1, request(1)).statusCode.value() shouldBe 201
        upload(t1, U1, request(2)).statusCode.value() shouldBe 201
        val repeat = upload(t1, U2, request(2))
        repeat.statusCode.value() shouldBe 200
        returns(t1).map { it["uploaded_by"] } shouldBe listOf(U1, U1)
        returns(t1).size shouldBe 2
        events(t1).size shouldBe 2
        queued(t1).size shouldBe 2
        val stored = inTx(t1, U2) { session.records.recorded(t1.toString(), "E1", "P1") }
        stored.map { it.uploadedBy } shouldBe listOf(U1, U1)
        // a call with no acting user is refused before anything is recorded
        shouldThrow<IllegalArgumentException> { upload(t1, " ", request(3)) }
        returns(t1).size shouldBe 2
    }
}
