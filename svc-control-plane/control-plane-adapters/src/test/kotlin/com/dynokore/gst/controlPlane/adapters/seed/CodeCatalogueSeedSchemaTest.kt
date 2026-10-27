package com.dynokore.gst.controlPlane.adapters.seed

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * The seed code catalogue against `rules/schemas/code-catalogue-pack-contents.schema.json` of the pinned
 * platform-contracts bundle, read from the staged bundle, never a copy (INV-AC-2431, INV-AC-10207).
 */
class CodeCatalogueSeedSchemaTest {
    private fun schemaErrors(text: String) =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(CodeCatalogueSeedFiles.schemaFile.readText())
            .validate(text, InputFormat.JSON)
            .map { it.message }

    @Test
    fun `INV-AC-2431 the seed contents validate against the pinned code catalogue schema`() {
        schemaErrors(CodeCatalogueSeedFiles.contents).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-10207 the schema check fails closed on a catalogue that breaks it`() {
        val root = CodeCatalogueSeedFiles.mapper.readTree(CodeCatalogueSeedFiles.contents) as ObjectNode
        schemaErrors((root.deepCopy() as ObjectNode).also { it.remove("complete") }.toString()).isEmpty() shouldBe false
        schemaErrors(CodeCatalogueSeedFiles.contents.replaceFirst("\"SR\"", "\"sr\"")).isEmpty() shouldBe false
        schemaErrors(CodeCatalogueSeedFiles.contents.replaceFirst("SUPPLY_STANDARD_RATED", "OTHER")).isEmpty() shouldBe
            false
    }
}
