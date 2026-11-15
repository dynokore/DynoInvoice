package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.OffboardingExportFinishedOutbox
import com.dynokore.gst.evidence.application.export.ExportResult
import com.dynokore.gst.evidence.application.export.ExportState
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-22949: the result event reaches the outbox in the caller's transaction, with the contract payload. */
class OffboardingExportFinishedOutboxTest {
    private val tenant = UUID.randomUUID()
    private val json = JsonMapper.builder().build()
    private val type = OffboardingExportFinishedOutbox.EVENT_TYPE
    private val clock = Clock.fixed(Instant.parse("2026-11-15T08:30:00Z"), ZoneOffset.UTC)

    private fun outbox(connection: Connection): OffboardingExportFinishedOutbox {
        val catalogue =
            EventCatalogue(
                listOf(CatalogueEntry(type, "gst.evidence.offboarding-export-finished.v1", setOf("evidence-svc"))),
            )
        val writer =
            JdbcOutboxWriter(
                DSL.using(connection, SQLDialect.POSTGRES),
                EvidenceDb.SCHEMA,
                "evidence-svc",
                "instance-1",
                catalogue,
                clock,
            )
        return OffboardingExportFinishedOutbox(writer, clock)
    }

    private fun publish(
        result: ExportResult,
        commit: Boolean,
    ) = EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
        connection.autoCommit = false
        SignedBindings.bind(connection, tenant)
        outbox(connection).publish(result)
        if (commit) connection.commit() else connection.rollback()
    }

    private fun rows(): List<Pair<String, String>> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select type, data::text, entity_ref from ${EvidenceDb.SCHEMA}.outbox where tenant_id = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                entities += rs.getString(3)
                                rs.getString(1) to rs.getString(2)
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private val entities = mutableListOf<String>()

    private fun result(
        state: ExportState,
        reason: String?,
    ) = ExportResult(
        tenant.toString(),
        "OFF-1",
        "EXP-1",
        state,
        if (state == ExportState.READY) listOf("TRANSMITTED_XML", "AUDIT_LOG") else emptyList(),
        reason,
    )

    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    /**
     * The contract schema as staged from the pinned platform-contracts bundle (evidence-contract build directory,
     * `platform-contracts/evidence/<bundle>/schemas`); a missing file fails the test.
     */
    private fun schemaErrors(data: JsonNode): List<String> {
        val root = File("../evidence-contract/build/contracts-repo/platform-contracts/evidence")
        val file =
            root
                .listFiles { f -> f.isDirectory }
                .orEmpty()
                .map { it.resolve("schemas/offboarding-export-finished-payload.schema.json") }
                .firstOrNull { it.isFile } ?: root.resolve("missing")
        check(file.isFile) { "schema file does not exist: $file" }
        return schemas.getSchema(file.readText()).validate(data).map { it.toString() }
    }

    @Test
    fun `INV-AC-22949 a READY result leaves one outbox row of the type that validates against the schema`() {
        publish(result(ExportState.READY, null), commit = true)
        val (rowType, data) = rows().single()
        rowType shouldBe type
        val payload = json.readTree(data)
        payload.get("tenantRef").asString() shouldBe tenant.toString()
        payload.get("offboardingRef").asString() shouldBe "OFF-1"
        payload.get("exportRef").asString() shouldBe "EXP-1"
        payload.get("state").asString() shouldBe "READY"
        payload.get("finishedAt").asString() shouldBe "2026-11-15T08:30:00Z"
        entities.single() shouldBe "00000000-0000-0000-0000-000000000000"
        schemaErrors(payload) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 a FAILED result carries the failure reason and validates against the schema`() {
        publish(result(ExportState.FAILED, "no source for SOURCE_DOCUMENT_REFERENCES"), commit = true)
        val payload = json.readTree(rows().single().second)
        payload.get("state").asString() shouldBe "FAILED"
        payload.get("failureReason").asString() shouldBe "no source for SOURCE_DOCUMENT_REFERENCES"
        schemaErrors(payload) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 a transaction rolled back after publish leaves no outbox row`() {
        publish(result(ExportState.READY, null), commit = false)
        rows() shouldBe emptyList()
    }
}
