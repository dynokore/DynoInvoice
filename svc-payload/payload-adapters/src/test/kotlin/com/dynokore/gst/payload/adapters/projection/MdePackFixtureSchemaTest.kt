package com.dynokore.gst.payload.adapters.projection

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.File

/**
 * INV-T-7178 (INV-Q-18101 A, INV-Q-18103 A): every row of the fixture pack validates against the MDE pack schema of the
 * pinned bundle, and the fixture-only rules hold. Fails closed: a missing schema, zero rows or any error is a failure.
 */
class MdePackFixtureSchemaTest {
    private val mapper = JsonMapper()
    private val prefix = "FIXTURE-ONLY (INV-T-7178; not product reference data): "
    private val submissionTypes = setOf("TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3")

    private fun schema(): JsonNode {
        val path = System.getProperty("gst.mde.schema") ?: error("system property gst.mde.schema is not set")
        val file = File(path)
        check(file.isFile) { "the pinned bundle schema is missing: $path" }
        return mapper.readTree(file)
    }

    private fun fixture(): JsonNode =
        mapper.readTree(
            javaClass.getResourceAsStream("/mde/mde-pack-fixture.json") ?: error("mde-pack-fixture.json is missing"),
        )

    private fun str(node: JsonNode): String = if (node.isString) node.stringValue() else ""

    /** The failures by name; an empty list is a pass. */
    private fun problems(
        fixture: JsonNode,
        schema: JsonNode,
    ): List<String> {
        val out = mutableListOf<String>()
        val rows = fixture.path("rows").values().toList()
        if (rows.isEmpty()) out += "the fixture has zero rows"
        val validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema)
        validator.validate(fixture).forEach { out += "schema: ${it.message}" }
        val ids: List<String> = rows.map { str(it.path("businessTermId")) }
        if (ids.size != ids.toSet().size) out += "businessTermId is not unique across rows: $ids"
        for (row in rows) {
            val id = str(row.path("businessTermId"))
            val versions: List<String> =
                row
                    .path(
                        "specifications",
                    ).values()
                    .map { str(it.path("specificationVersion")) }
            if (versions.size != versions.toSet().size) out += "$id: specificationVersion is not unique within the row"
            if (!str(row.path("sourceReference")).startsWith(prefix)) {
                out +=
                    "$id: sourceReference lacks the fixture-only prefix"
            }
            if (row
                    .path("submissionTypes")
                    .values()
                    .map { str(it) }
                    .toSet() !=
                submissionTypes
            ) {
                out += "$id: submissionTypes is not the four members"
            }
            val dataType = str(row.path("dataType"))
            if (id == "IBT-001" && dataType != "IDENTIFIER") out += "$id: dataType is $dataType"
            if (id == "IBT-002" && dataType != "DATE") out += "$id: dataType is $dataType"
        }
        return out
    }

    private fun mutate(change: (ArrayNode) -> Unit): JsonNode {
        val copy = fixture().deepCopy()
        change(copy.path("rows") as ArrayNode)
        return copy
    }

    private fun row(
        rows: ArrayNode,
        id: String,
    ) = rows.values().first { str(it.path("businessTermId")) == id } as ObjectNode

    @Test
    fun `FixtureSchema the fixture validates against the pinned bundle schema`() {
        problems(fixture(), schema()) shouldBe emptyList()
        fixture()
            .path("rows")
            .values()
            .map { str(it.path("businessTermId")) }
            .toSet() shouldBe
            setOf("IBT-001", "IBT-002", "IBT-023", "IBT-024", "IBT-030", "IBT-031", "IBT-047")
    }

    @Test
    fun `FixtureSchema fails closed on zero rows and a missing schema property`() {
        problems(mapper.readTree("""{"rows":[]}"""), schema()).isNotEmpty() shouldBe true
        problems(mapper.readTree("{}"), schema()).isNotEmpty() shouldBe true
    }

    @Test
    fun `FixtureSchema probes each make the check fail`() {
        val s = schema()
        val probes: Map<String, (ArrayNode) -> Unit> =
            mapOf(
                "no canonicalSource" to { r -> row(r, "IBT-030").remove("canonicalSource") },
                "id IBT-47" to { r -> row(r, "IBT-030").put("businessTermId", "IBT-47") },
                "id BT-1" to { r -> row(r, "IBT-001").put("businessTermId", "BT-1") },
                "same id twice" to { r -> r.add(row(r, "IBT-030").deepCopy()) },
                "no prefix" to { r -> row(r, "IBT-030").put("sourceReference", "INV-REQ-485") },
                "look-alike prefix" to { r ->
                    row(r, "IBT-030").put("sourceReference", prefix.replace("FIXTURE-ONLY", "Fixture-only") + "x")
                },
                "STRING on IBT-002" to { r -> row(r, "IBT-002").put("dataType", "STRING") },
                "Identifier on IBT-001" to { r -> row(r, "IBT-001").put("dataType", "Identifier") },
                "submission types short" to { r -> (row(r, "IBT-030").path("submissionTypes") as ArrayNode).remove(0) },
            )
        for ((name, change) in probes) {
            (name to problems(mutate(change), s).isNotEmpty()) shouldBe (name to true)
        }
    }
}
