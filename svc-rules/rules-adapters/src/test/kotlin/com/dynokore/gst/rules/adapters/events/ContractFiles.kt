package com.dynokore.gst.rules.adapters.events

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import org.yaml.snakeyaml.Yaml

/** The pinned rules contract as the rules-contract module ships it (`contract/asyncapi.yaml`, `contract/schemas/`). */
internal object ContractFiles {
    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    private fun text(path: String): String =
        checkNotNull(ContractFiles::class.java.getResourceAsStream(path)) { "the contract resource $path is missing" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }

    @Suppress("UNCHECKED_CAST")
    private val asyncApi: Map<String, Any?> = Yaml().load<Map<String, Any?>>(text("/contract/asyncapi.yaml"))

    /** Channel name to address (`gst.rules.rule-pack-staged.v1`), for every channel of the document. */
    @Suppress("UNCHECKED_CAST")
    val channelAddresses: Map<String, String> =
        (asyncApi["channels"] as Map<String, Map<String, Any?>>).mapValues { (_, channel) ->
            channel["address"] as String
        }

    /** The message `name` (the CloudEvents type) and the payload schema file of the one message on [channel]. */
    @Suppress("UNCHECKED_CAST")
    fun message(channel: String): Pair<String, String> {
        val messageRef =
            (
                (asyncApi["channels"] as Map<String, Map<String, Any?>>).getValue(
                    channel,
                )["messages"] as Map<String, Map<String, String>>
            ).values
                .single()
                .getValue("\$ref")
        val message = (asyncApi["components"] as Map<String, Any?>)["messages"] as Map<String, Map<String, Any?>>
        val definition = message.getValue(messageRef.substringAfterLast('/'))
        val payloadRef = (definition["payload"] as Map<String, String>).getValue("\$ref")
        return (definition["name"] as String) to payloadRef.substringAfterLast('/')
    }

    /** The validation errors of [payloadJson] against the payload schema the AsyncAPI document names for [channel]. */
    fun errors(
        channel: String,
        payloadJson: String,
    ): List<String> {
        val (_, schemaFile) = message(channel)
        return schemas
            .getSchema(text("/contract/schemas/$schemaFile"))
            .validate(payloadJson, InputFormat.JSON)
            .map { it.toString() }
    }
}
