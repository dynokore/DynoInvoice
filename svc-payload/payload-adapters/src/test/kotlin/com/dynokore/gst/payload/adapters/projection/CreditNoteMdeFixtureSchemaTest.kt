package com.dynokore.gst.payload.adapters.projection

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

/** INV-T-7187: the credit-note fixture file validates against the pinned MDE pack schema. Fails closed. */
class CreditNoteMdeFixtureSchemaTest {
    private val mapper = JsonMapper()
    private val prefix = "FIXTURE-ONLY (INV-T-7187; not product reference data): "

    private fun schema(): JsonNode {
        val path = System.getProperty("gst.mde.schema") ?: error("system property gst.mde.schema is not set")
        val file = File(path)
        check(file.isFile) { "the pinned bundle schema is missing: $path" }
        return mapper.readTree(file)
    }

    private fun load(name: String): JsonNode =
        mapper.readTree(javaClass.getResourceAsStream(name) ?: error("$name is missing"))

    private fun str(node: JsonNode): String = if (node.isString) node.stringValue() else ""

    private fun problems(
        fixture: JsonNode,
        main: JsonNode,
        schema: JsonNode,
    ): List<String> {
        val out = mutableListOf<String>()
        val rows = fixture.path("rows").values().toList()
        if (rows.size != 1) out += "the fixture has ${rows.size} rows, expected exactly one"
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schema)
            .validate(fixture)
            .forEach { out += "schema: ${it.message}" }
        val mainIds =
            main
                .path("rows")
                .values()
                .map { str(it.path("businessTermId")) }
                .toSet()
        for (row in rows) {
            val id = str(row.path("businessTermId"))
            if (id != "IBT-022") out += "businessTermId is $id"
            if (row.path("documentTypeCodes").values().map { str(it) } !=
                listOf("381")
            ) {
                out += "$id: documentTypeCodes is not [381]"
            }
            if (id in mainIds) out += "$id is a businessTermId of mde-pack-fixture.json"
            if (!str(row.path("sourceReference")).startsWith(prefix)) out += "$id: sourceReference lacks the prefix"
        }
        return out
    }

    @Test
    fun `CreditNoteFixtureSchema the file validates and holds the single IBT-022 row for 381`() {
        problems(load("/mde/mde-pack-credit-note-fixture.json"), load("/mde/mde-pack-fixture.json"), schema()) shouldBe
            emptyList()
    }

    @Test
    fun `CreditNoteFixtureSchema fails closed on zero rows and on a row that breaks a rule`() {
        val main = load("/mde/mde-pack-fixture.json")
        val s = schema()
        problems(mapper.readTree("""{"rows":[]}"""), main, s).isNotEmpty() shouldBe true
        val text =
            javaClass
                .getResourceAsStream(
                    "/mde/mde-pack-credit-note-fixture.json",
                )!!
                .readBytes()
                .decodeToString()
        for (broken in listOf(text.replace("\"381\"", "\"380\""), text.replace("IBT-022", "IBT-030"))) {
            problems(mapper.readTree(broken), main, s).isNotEmpty() shouldBe true
        }
    }
}
