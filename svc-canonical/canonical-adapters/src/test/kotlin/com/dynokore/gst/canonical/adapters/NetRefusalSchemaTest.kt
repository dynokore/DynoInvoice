package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.domain.netting.NetRefusal
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.File
import com.networknt.schema.SpecificationVersion as SchemaDialect

/** The pinned contract documents of the net derivation tests, read the way the other contract-schema tests read them. */
internal object NetContractSchemas {
    val mapper = ObjectMapper()

    private fun contractDir() =
        File(checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" })

    /** A schema of the canonical contract of the pinned bundle. */
    fun canonicalSchemaText(name: String): String = File(contractDir(), "schemas/$name").readText()

    /** A schema of the submission contract: the staged bundle keeps the services that canonical-svc does not stage. */
    fun submissionSchemaText(name: String): String =
        File(contractDir().parentFile.parentFile.parentFile, ".bundle/submission/schemas/$name").readText()

    private val registry =
        SchemaRegistry.withDefaultDialect(SchemaDialect.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    /** The validation errors of [instance] against [schema] (a whole schema document or a definition of one). */
    fun errors(
        schema: String,
        instance: String,
    ): List<String> = registry.getSchema(schema).validate(mapper.readTree(instance)).map { it.toString() }

    /** The `canonicalDocument` definition of the CanonicalVersionCreated schema, as a schema of its own. */
    fun canonicalDocumentSchema(): String =
        mapper.readTree(canonicalSchemaText("canonical-version-created-payload.schema.json"))["properties"]["canonicalDocument"]
            .toString()
}

class NetRefusalSchemaTest {
    private val refusedSchema = "net-derivation-refused-payload.schema.json"

    private fun reasonEnum(schema: JsonNode): Set<String> =
        (schema["properties"]["reason"]["enum"] as ArrayNode).toList().map { it.asString() }.toSet()

    private fun enumMatches(schemaText: String): Boolean =
        reasonEnum(NetContractSchemas.mapper.readTree(schemaText)) == NetRefusal.entries.map { it.name }.toSet()

    @Test
    fun `INV-AC-25342 the NetRefusal names equal the reason enum of the pinned refused schema`() {
        enumMatches(NetContractSchemas.canonicalSchemaText(refusedSchema)) shouldBe true
    }

    @Test
    fun `INV-AC-25342 a schema enum with the look-alike member NET_NOT_POSITIVES makes the comparison fail`() {
        val copy = NetContractSchemas.mapper.readTree(NetContractSchemas.canonicalSchemaText(refusedSchema))
        ((copy["properties"]["reason"] as ObjectNode)["enum"] as ArrayNode).add("NET_NOT_POSITIVES")
        enumMatches(copy.toString()) shouldBe false
    }

    @Test
    fun `INV-AC-25342 a schema enum that lacks a name and a case variant both make the comparison fail`() {
        val missing = NetContractSchemas.mapper.readTree(NetContractSchemas.canonicalSchemaText(refusedSchema))
        ((missing["properties"]["reason"] as ObjectNode)["enum"] as ArrayNode).remove(0)
        enumMatches(missing.toString()) shouldBe false
        val lower = NetContractSchemas.mapper.readTree(NetContractSchemas.canonicalSchemaText(refusedSchema))
        ((lower["properties"]["reason"] as ObjectNode)["enum"] as ArrayNode).set(0, "not_a_credit_note")
        enumMatches(lower.toString()) shouldBe false
    }
}
