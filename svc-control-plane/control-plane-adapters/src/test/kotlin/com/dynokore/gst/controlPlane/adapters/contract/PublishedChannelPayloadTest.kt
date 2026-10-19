package com.dynokore.gst.controlPlane.adapters.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * A test per published channel (INV-T-4984): the payload of each message of the pinned AsyncAPI document validates
 * against the schema the document names. The schema comes from the pinned platform-contracts release staged by
 * control-plane-contract, never a copy.
 */
class PublishedChannelPayloadTest {
    private val dir = File(System.getProperty("gst.contracts.dir"))

    private fun schemaOf(message: String): File {
        val root = Yaml().load<Map<String, Any?>>(File(dir, "asyncapi.yaml").readText())
        val messages = (root["components"] as Map<*, *>)["messages"] as Map<*, *>
        val ref = ((messages[message] as Map<*, *>)["payload"] as Map<*, *>)["\$ref"] as String
        return File(dir, ref).canonicalFile
    }

    private fun errors(
        schema: File,
        payload: String,
    ) = SchemaRegistry
        .withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
        ).getSchema(schema.readText())
        .validate(payload, InputFormat.JSON)

    @Test
    fun `control-planeExampleHappened payload validates against its schema and a payload without entityRef does not`() {
        val schema = schemaOf("ExampleHappened")
        errors(schema, """{"entityRef":"E-1"}""").isEmpty() shouldBe true
        errors(schema, """{"entityRef":"E-1","extra":1}""").isEmpty() shouldBe true
        errors(schema, """{}""").isEmpty() shouldBe false
        errors(schema, """{"entityRef":5}""").isEmpty() shouldBe false
    }

    @Test
    fun `every channel message of the pinned document has a payload test`() {
        val root = Yaml().load<Map<String, Any?>>(File(dir, "asyncapi.yaml").readText())
        val messages = ((root["components"] as Map<*, *>)["messages"] as Map<*, *>).keys.map { it.toString() }
        // The one published message today; a second one fails here until its own payload test is added above.
        messages shouldBe listOf("ExampleHappened")
    }
}
