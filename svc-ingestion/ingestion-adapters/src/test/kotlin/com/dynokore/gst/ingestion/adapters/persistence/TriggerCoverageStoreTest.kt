package com.dynokore.gst.ingestion.adapters.persistence

import com.dynokore.gst.ingestion.adapters.IngestionDb
import com.dynokore.gst.ingestion.adapters.SignedBindings
import com.dynokore.gst.ingestion.adapters.events.TriggerCoverageChangedOutbox
import com.dynokore.gst.ingestion.application.triggers.TriggerCoverageChanged
import com.dynokore.gst.ingestion.domain.triggers.CoverageRecord
import com.dynokore.gst.ingestion.domain.triggers.TriggerClass
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-20937 (INV-REQ-788): the coverage table holds one tenant-isolated row per tenant, entity and class, and the
 * store writes the row and its event in one transaction. A real PostgreSQL database migrated by this module's
 * migrations, the jOOQ store and the real outbox writer, over the `ingestion_runtime` login.
 */
class TriggerCoverageStoreTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
    private val actor = UUID.randomUUID()
    private val catalogue =
        EventCatalogue(
            listOf(
                CatalogueEntry(
                    TriggerCoverageChangedOutbox.TYPE,
                    TriggerCoverageChangedOutbox.TOPIC,
                    setOf("ingestion-svc"),
                    contentTopic = false,
                    retainedWhileResolvable = false,
                ),
            ),
        )

    /** One transaction of the runtime login bound to [tenant]: committed when [block] returns, else rolled back. */
    private fun <T> inTransaction(
        tenant: UUID,
        block: (DSLContext, TriggerCoverageJooqStore, TriggerCoverageChangedOutbox) -> T,
    ): T =
        IngestionDb.connect(IngestionDb.RUNTIME, IngestionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            try {
                SignedBindings.bind(connection, tenant)
                val dsl = DSL.using(connection, SQLDialect.POSTGRES)
                val writer = JdbcOutboxWriter(dsl, IngestionDb.SCHEMA, "ingestion-svc", "i-1", catalogue, clock)
                val store = TriggerCoverageJooqStore(dsl, clock)
                val result = block(dsl, store, TriggerCoverageChangedOutbox(writer, clock))
                connection.commit()
                result
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

    private fun save(
        tenant: UUID,
        entity: String,
        triggerClass: TriggerClass,
        record: CoverageRecord,
    ) = inTransaction(tenant) { _, store, outbox ->
        store.save(tenant, entity, triggerClass, record, actor)
        outbox.publish(TriggerCoverageChanged(tenant, entity, triggerClass, record, actor))
    }

    private fun <T> superuser(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): T =
        IngestionDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    read(rows)
                }
            }
        }

    private fun count(sql: String): Int = superuser(sql) { it.getInt(1) }

    private fun coverageRows(tenant: UUID): Int =
        count("select count(*) from ingestion.trigger_coverage where tenant_id = '$tenant'")

    private fun outboxRows(tenant: UUID): Int =
        count(
            "select count(*) from ingestion.outbox where tenant_id = '$tenant' " +
                "and type = 'com.dynokore.gst.ingestion.TriggerCoverageChanged.v1' " +
                "and topic = 'gst.ingestion.trigger-coverage-changed.v1'",
        )

    private fun rawInsert(
        connection: Connection,
        tenant: UUID,
        entity: String,
    ) = connection
        .prepareStatement(
            "insert into ingestion.trigger_coverage (tenant_id, entity_ref, trigger_class, status, adapter_ref, " +
                "recorded_by, recorded_at) values (?, ?, 'OUTBOUND_DOCUMENT_ISSUED', 'MAPPED', 'erp-ar', ?, now())",
        ).use {
            it.setObject(1, tenant)
            it.setString(2, entity)
            it.setObject(3, actor)
            it.executeUpdate()
        }

    @Test
    fun `INV-AC-20937 a save writes one row and a second save for the same key updates it in place`() {
        val tenant = UUID.randomUUID()
        save(tenant, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, CoverageRecord.Mapped("erp-ar"))
        save(tenant, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, CoverageRecord.Waived(REVIEW))
        coverageRows(tenant) shouldBe 1
        inTransaction(tenant) { _, store, _ ->
            store.find(tenant, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED) shouldBe CoverageRecord.Waived(REVIEW)
        }
    }

    @Test
    fun `INV-AC-20937 a save for another class or another entity writes another row`() {
        val tenant = UUID.randomUUID()
        save(tenant, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, CoverageRecord.Mapped("erp-ar"))
        save(tenant, "E-1", TriggerClass.INBOUND_DOCUMENT_RECORDED, CoverageRecord.Mapped("erp-ap"))
        val elsewhere = CoverageRecord.CoveredElsewhere("Peppol AP", "EV-9")
        save(tenant, "E-2", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, elsewhere)
        coverageRows(tenant) shouldBe 3
    }

    @Test
    fun `INV-AC-20937 a second plain insert for the same key is refused by the unique key`() {
        val tenant = UUID.randomUUID()
        save(tenant, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, CoverageRecord.Mapped("erp-ar"))
        IngestionDb.connect(IngestionDb.RUNTIME, IngestionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            shouldThrow<SQLException> { rawInsert(connection, tenant, "E-1") }.sqlState shouldBe "23505"
            connection.rollback()
        }
        coverageRows(tenant) shouldBe 1
    }

    @Test
    fun `INV-AC-20937 the row stores status, the members of its record, the acting user id and the time`() {
        val tenant = UUID.randomUUID()
        val elsewhere = CoverageRecord.CoveredElsewhere("CES portal", "EV-1")
        save(tenant, "E-1", TriggerClass.THIRD_PARTY_ISSUANCE, elsewhere)
        val facts =
            superuser(
                "select status, covering_solution, evidence_reference, adapter_ref, review_date, recorded_by, " +
                    "recorded_at from ingestion.trigger_coverage where tenant_id = '$tenant'",
            ) {
                listOf(
                    it.getString(1),
                    it.getString(2),
                    it.getString(3),
                    it.getString(4),
                    it.getString(5),
                    it.getString(6),
                    it.getTimestamp(7).toInstant().toString(),
                )
            }
        facts shouldBe
            listOf(
                "COVERED_BY_OTHER_SOLUTION",
                "CES portal",
                "EV-1",
                null,
                null,
                actor.toString(),
                "2026-11-12T10:00:00Z",
            )
    }

    @Test
    fun `INV-AC-20937 no column of the table holds a role name`() {
        val columns =
            IngestionDb.superuser().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "select column_name from information_schema.columns " +
                                "where table_schema = 'ingestion' and table_name = 'trigger_coverage' " +
                                "and (column_name like '%role%' or column_name like '%permission%')",
                        ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                }
            }
        columns.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-20937 a save writes one outbox row of the catalogued type and topic in the same transaction`() {
        val tenant = UUID.randomUUID()
        save(tenant, "E-1", TriggerClass.AGGREGABLE_BATCH_CLOSED, CoverageRecord.Mapped("pos"))
        coverageRows(tenant) shouldBe 1
        outboxRows(tenant) shouldBe 1
        val data =
            superuser("select data::text from ingestion.outbox where tenant_id = '$tenant'") { it.getString(1) }
        listOf("\"entityRef\": \"E-1\"", "\"status\": \"MAPPED\"", "\"adapterRef\": \"pos\"")
            .plus("\"actorUserId\": \"$actor\"")
            .forEach { data.contains(it) shouldBe true }
    }

    @Test
    fun `INV-AC-20937 a failure before commit leaves neither the row nor the event`() {
        val tenant = UUID.randomUUID()
        shouldThrow<IllegalStateException> {
            inTransaction(tenant) { _, store, outbox ->
                val record = CoverageRecord.Mapped("erp-ar")
                store.save(tenant, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, record, actor)
                val event = TriggerCoverageChanged(tenant, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, record, actor)
                outbox.publish(event)
                error("failure before commit")
            }
        }
        coverageRows(tenant) shouldBe 0
        outboxRows(tenant) shouldBe 0
    }

    @Test
    fun `INV-AC-20937 tenant B sees none of the rows of tenant A`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        save(a, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, CoverageRecord.Mapped("erp-ar"))
        inTransaction(b) { dsl, store, _ ->
            store.find(a, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED) shouldBe null
            dsl.fetchCount(DSL.table(DSL.name("ingestion", "trigger_coverage"))) shouldBe 0
        }
    }

    @Test
    fun `INV-AC-20937 tenant B cannot insert a row with the tenant id of tenant A`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        shouldThrow<DataAccessException> {
            inTransaction(b) { _, store, _ ->
                store.save(a, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, CoverageRecord.Mapped("erp-ar"), actor)
            }
        }
        coverageRows(a) shouldBe 0
    }

    @Test
    fun `INV-AC-20937 the runtime role holds select, insert and update only and a delete is refused`() {
        val tenant = UUID.randomUUID()
        save(tenant, "E-1", TriggerClass.OUTBOUND_DOCUMENT_ISSUED, CoverageRecord.Mapped("erp-ar"))
        val privileges =
            listOf("select", "insert", "update", "delete", "truncate", "references", "trigger").associateWith {
                val sql = "select has_table_privilege('ingestion_runtime', 'ingestion.trigger_coverage', '$it')"
                superuser(sql) { r -> r.getBoolean(1) }
            }
        privileges.filterValues { it }.keys shouldBe setOf("select", "insert", "update")
        IngestionDb.connect(IngestionDb.RUNTIME, IngestionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            shouldThrow<SQLException> {
                connection.createStatement().use { it.executeUpdate("delete from ingestion.trigger_coverage") }
            }.sqlState shouldBe "42501"
            connection.rollback()
        }
        coverageRows(tenant) shouldBe 1
    }

    private companion object {
        val REVIEW: LocalDate = LocalDate.parse("2027-03-31")
    }
}
