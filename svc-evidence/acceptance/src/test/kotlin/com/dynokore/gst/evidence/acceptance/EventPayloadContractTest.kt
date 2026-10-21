package com.dynokore.gst.evidence.acceptance

import com.dynokore.gst.evidence.bootstrap.EvidenceEvents
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper
import java.io.File

/**
 * A test per published channel (INV-T-5547): the sample payload of each channel of `asyncapi.yaml` validates against
 * the JSON Schema the channel's message names, and the same payload without a required property does not. Deny by
 * default: a channel of the contract with no sample here fails, so a channel added to the contract needs its test.
 */
class EventPayloadContractTest {
    private val contractDir = File(System.getProperty("gst.contract.dir"))
    private val asyncapi: Map<String, Any?> = Yaml().load(File(contractDir, "asyncapi.yaml").readText())
    private val mapper = ObjectMapper()
    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    /** Message key of components.messages to its sample payload, written from the message's JSON Schema. */
    private val samples: Map<String, String> =
        mapOf(
            "EvidenceIntegrityFailed" to
                """{"range":{"from":"2026-01-01T00:00:00Z","to":"2026-02-01T00:00:00Z"},
                |"failingRecord":{"id":"r-1"}}
                """.trimMargin(),
            "PriorSubmissionEvidenceImported" to PRIOR_IMPORTED,
            "ExampleHappened" to """{"entityRef":"E-001"}""",
        )

    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    /** Channel address to message key, read from the contract. */
    private fun channels(): Map<String, String> =
        map(asyncapi["channels"]).values.associate { channel ->
            val c = map(channel)
            val message = map(c["messages"]).keys.single()
            c["address"] as String to message
        }

    private fun schemaFile(message: String): File {
        val ref = map(map(map(asyncapi["components"])["messages"])[message])["payload"]
        return File(contractDir, (map(ref)["\$ref"] as String).removePrefix("./"))
    }

    private fun errors(
        message: String,
        payload: String,
    ): List<String> =
        schemas.getSchema(schemaFile(message).readText()).validate(mapper.readTree(payload)).map { it.toString() }

    @TestFactory
    fun `the payload of each published channel conforms to its AsyncAPI schema`(): List<DynamicTest> =
        channels().map { (address, message) ->
            dynamicTest("channel $address") {
                val sample =
                    checkNotNull(samples[message]) { "channel $address (message $message) has no sample payload" }
                errors(message, sample).shouldBeEmpty()
                val required: List<String> =
                    mapper
                        .readTree(
                            schemaFile(message).readText(),
                        ).get("required")
                        .toList()
                        .map {
                            it.asString()
                        }
                required.shouldNotBeEmpty()
                required.forEach { property ->
                    val without =
                        mapper
                            .readTree(
                                sample,
                            ).also { (it as tools.jackson.databind.node.ObjectNode).remove(property) }
                    errors(message, without.toString()).shouldNotBeEmpty()
                }
            }
        }

    @Test
    fun `every sample belongs to a channel of the contract`() {
        samples.keys shouldBe channels().values.toSet()
    }

    @Test
    fun `the events evidence may emit are channels of the contract with the message name as the type`() {
        val byAddress = channels()
        EvidenceEvents.topicByType.forEach { (type, topic) ->
            val message = checkNotNull(byAddress[topic]) { "$topic is not a channel of the contract" }
            val name = map(map(map(asyncapi["components"])["messages"])[message])["name"]
            name shouldBe type
        }
    }

    private companion object {
        val PRIOR_IMPORTED =
            """{"entityRef":"E-0001","documentNumber":"INV-100","transactionDate":"2026-02-27",
                |"acknowledgementRef":"ACK-LEGACY-0001","priorSolution":"LegacyBill 7"}
            """.trimMargin()
    }
}
