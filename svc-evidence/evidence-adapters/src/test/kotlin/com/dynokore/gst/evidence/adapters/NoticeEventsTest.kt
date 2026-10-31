package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqNoticeEvents
import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.PrivacyCaseFacts
import com.dynokore.gst.evidence.domain.cases.RequestKind
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-11708 (INV-Q-6851 A): `PrivacyNoticeDue` reaches the outbox in the caller's transaction as a platform event
 * on the tenant-wide entity, with the five fields of its contract schema and no other property.
 */
class NoticeEventsTest {
    private val tenant = UUID.randomUUID()
    private val json = JsonMapper.builder().build()
    private val type = JooqNoticeEvents.EVENT_TYPE
    private val fields = setOf("caseId", "requestKind", "receivedOn", "responseDeadline", "noticeLeadDays")

    private val record =
        CaseRecord(
            UUID.randomUUID(),
            CaseKind.PRIVACY,
            CaseStatus.OPEN,
            "subject-secret",
            Instant.now(),
            null,
            PrivacyCaseFacts(
                RequestKind.CORRECTION,
                "REQ-secret-reference",
                LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-03-31"),
                30,
                null,
                null,
            ),
        )

    private fun events(connection: Connection): JooqNoticeEvents {
        val catalogue =
            EventCatalogue(
                listOf(CatalogueEntry(type, "gst.platform.privacy-notice-due.v1", setOf("evidence-svc"))),
            )
        val writer =
            JdbcOutboxWriter(
                DSL.using(connection, SQLDialect.POSTGRES),
                EvidenceDb.SCHEMA,
                "evidence-svc",
                "instance-1",
                catalogue,
                Clock.systemUTC(),
            )
        return JooqNoticeEvents(writer)
    }

    private fun recordIn(commit: Boolean) {
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            events(connection).record(TenantId(tenant), record, 14)
            if (commit) connection.commit() else connection.rollback()
        }
    }

    private class Row(
        val type: String,
        val entityRef: String,
        val subject: String,
        val data: String,
    )

    private fun rows(): List<Row> =
        EvidenceDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select type, entity_ref, subject, data::text from ${EvidenceDb.SCHEMA}.outbox " +
                        "where tenant_id = ? and subject = ?",
                ).use { statement ->
                    statement.setObject(1, tenant)
                    statement.setString(2, record.caseId.toString())
                    statement.executeQuery().use {
                        generateSequence {
                            if (it.next()) {
                                Row(
                                    it.getString(1),
                                    it.getString(2),
                                    it.getString(3),
                                    it.getString(4),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    /** The contract schema as staged from the pinned platform-contracts bundle. */
    private fun schemaProperties(): Set<String> {
        val file =
            File("../evidence-contract/build/contracts-repo/.bundle/service-runtime/schemas")
                .resolve("privacy-notice-due-payload.schema.json")
        return json
            .readTree(file.readText())
            .get("properties")
            .propertyNames()
            .toSet()
    }

    @Test
    fun `INV-AC-11708 one record leaves one outbox row of the type with the five values and the tenant-wide entity`() {
        recordIn(commit = true)
        val row = rows().single()
        row.type shouldBe type
        row.entityRef shouldBe "00000000-0000-0000-0000-000000000000"
        val payload = json.readTree(row.data)
        payload.get("caseId").asString() shouldBe record.caseId.toString()
        payload.get("requestKind").asString() shouldBe "CORRECTION"
        LocalDate.parse(payload.get("receivedOn").asString()) shouldBe LocalDate.parse("2026-03-01")
        LocalDate.parse(payload.get("responseDeadline").asString()) shouldBe LocalDate.parse("2026-03-31")
        payload.get("noticeLeadDays").asInt() shouldBe 14
    }

    @Test
    fun `INV-AC-11708 a transaction rolled back after record leaves no row`() {
        recordIn(commit = false)
        rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-11708 the payload property names equal the five of the contract schema, no subject and no reference`() {
        recordIn(commit = true)
        val names = json.readTree(rows().single().data).propertyNames().toSet()
        names shouldBe fields
        schemaProperties() shouldBe fields
    }
}
