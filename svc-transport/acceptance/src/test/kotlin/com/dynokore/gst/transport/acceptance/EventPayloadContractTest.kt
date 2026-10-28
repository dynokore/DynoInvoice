package com.dynokore.gst.transport.acceptance

import com.dynokore.gst.transport.bootstrap.TransportEvents
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
 * A test per published channel (INV-T-4981): the sample payload of each channel of `asyncapi.yaml` validates against
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
            "AcknowledgementReceived" to
                """{"adapter":"primary","apNotificationId":"N-000002","docUuid":"7d0c1c55-2b1a-4c47-9d40-5f5f1b7f3c01",
                |"version":1,"acknowledgementId":"IRAS-ACK-0000001"}
                """.trimMargin(),
            "NetworkDocumentReceived" to
                """{"adapter":"primary","peppolMessageId":"msg-1","documentReference":"INV-2026-001"}""",
            "AccessPointRouteChanged" to """{"connectionRef":"CONN-01","entityRefs":["E-001"],"version":1}""",
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

    private val ackBase =
        """"adapter":"primary","apNotificationId":"N-000002","docUuid":"7d0c1c55-2b1a-4c47-9d40-5f5f1b7f3c01",
        |"version":1,"acknowledgementId":"IRAS-ACK-0000001""""

    @Test
    fun `an acknowledgement with outcome REJECTED and two reasons is valid`() {
        val reasons = """[{"code":"IRAS-01","message":"first"},{"code":"IRAS-02","message":"second"}]"""
        errors("AcknowledgementReceived", """{$ackBase,"outcome":"REJECTED","reasons":$reasons}""".trimMargin())
            .shouldBeEmpty()
    }

    @Test
    fun `an acknowledgement with outcome PENDING is refused`() {
        errors("AcknowledgementReceived", """{$ackBase,"outcome":"PENDING"}""".trimMargin()).shouldNotBeEmpty()
    }

    @Test
    fun `an acknowledgement whose reason has no code is refused`() {
        errors(
            "AcknowledgementReceived",
            """{$ackBase,"outcome":"REJECTED","reasons":[{"message":"no code"}]}""".trimMargin(),
        ).shouldNotBeEmpty()
    }

    @Test
    fun `every sample belongs to a channel of the contract`() {
        samples.keys shouldBe channels().values.toSet()
    }

    @Test
    fun `the events transport may emit are channels of the contract with the message name as the type`() {
        val byAddress = channels()
        TransportEvents.topicByType.forEach { (type, topic) ->
            val message = checkNotNull(byAddress[topic]) { "$topic is not a channel of the contract" }
            val name = map(map(map(asyncapi["components"])["messages"])[message])["name"]
            name shouldBe type
        }
    }
}
