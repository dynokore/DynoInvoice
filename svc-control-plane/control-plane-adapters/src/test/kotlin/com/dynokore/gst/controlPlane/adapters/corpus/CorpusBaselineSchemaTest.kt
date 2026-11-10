package com.dynokore.gst.controlPlane.adapters.corpus

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** The three baselines are in the published schema format of their type (platform-contracts rules/schemas). */
class CorpusBaselineSchemaTest {
    @TempDir
    lateinit var tmp: Path

    private val tree by lazy { CorpusTree(tmp) }

    private fun problems(
        schemaName: String,
        document: String,
    ): List<String> {
        val schema =
            File(checkNotNull(System.getProperty("gst.contracts.bundle.dir")) { "gst.contracts.bundle.dir is not set" })
                .resolve("rules/schemas/$schemaName")
                .also { check(it.isFile) { "the staged platform-contracts bundle holds no $it" } }
        return SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schema.readText())
            .validate(document, InputFormat.JSON)
            .map { it.message }
    }

    @Test
    fun `INV-AC-9790 each baseline validates against the published schema of its pack type`() {
        problems("code-catalogue-pack-contents.schema.json", tree.text("code-catalogue-pack.json")) shouldBe emptyList()
        problems("scenario-pack-handlers.schema.json", tree.text("scenario-pack.json")) shouldBe emptyList()
        problems("scope-pack-contents.schema.json", tree.text("scope-pack.json")) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-9790 the code catalogue baseline holds the 35 codes and the scope baseline a cancellation row`() {
        tree.read("code-catalogue-pack.json").get("codes").size() shouldBe 35
        val rows = tree.read("scope-pack.json").get("classificationTable")
        val cancellation =
            (0 until rows.size()).map { rows.get(it) }.single {
                it.get("upstreamFunction").asString() == "ADJUSTMENT" &&
                    it.get("upstreamKind").asString() == "cancellation" &&
                    it.get("signOfTotal").asString() == "NEGATIVE"
            }
        cancellation.get("documentTypeCode").asString() shouldBe "381"
        cancellation.get("cancellation").asBoolean() shouldBe true
    }
}
