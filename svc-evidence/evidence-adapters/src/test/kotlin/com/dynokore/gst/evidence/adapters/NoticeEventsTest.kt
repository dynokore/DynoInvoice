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
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
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

    /** The contract schema as staged from the pinned platform-contracts bundle; a missing file fails the test. */
    private fun schemaNode(): JsonNode {
        val file =
            File("../evidence-contract/build/contracts-repo/.bundle/service-runtime/schemas")
                .resolve("privacy-notice-due-payload.schema.json")
        check(file.isFile) { "schema file does not exist: $file" }
        return json.readTree(file.readText())
    }

    private fun schemaProperties(): Set<String> = schemaNode().get("properties").propertyNames().toSet()

    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    private fun schemaErrors(data: JsonNode): List<String> =
        schemas.getSchema(schemaNode().toString()).validate(data).map { it.toString() }

    private fun storedPayload(): ObjectNode {
        recordIn(commit = true)
        return json.readTree(rows().last().data) as ObjectNode
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

    @Test
    fun `INV-AC-11708 the stored payload validates against the pinned contract schema`() {
        schemaErrors(storedPayload()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-11708 the schema properties and required both equal the five`() {
        schemaProperties() shouldBe fields
        val required = schemaNode().get("required")
        (0 until required.size()).map { required.get(it).asString() }.toSet() shouldBe fields
    }

    @Test
    fun `INV-AC-11708 a sixth property is refused by the property-name test, not by the open schema`() {
        val payload = storedPayload().put("subjectRef", "x")
        (payload.propertyNames().toSet() == fields) shouldBe false
        schemaErrors(payload) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-11708 each bad value fails the schema validation`() {
        val good = storedPayload()
        schemaErrors(good.deepCopy().put("receivedOn", "2026-13-45")).shouldNotBeEmpty()
        schemaErrors(good.deepCopy().put("requestKind", "OTHER")).shouldNotBeEmpty()
        schemaErrors(good.deepCopy().put("noticeLeadDays", 0)).shouldNotBeEmpty()
        schemaErrors(good.deepCopy().also { it.remove("caseId") }).shouldNotBeEmpty()
    }
}
