package com.dynokore.platform.contracttests

import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.core.model.V4Interaction
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.serviceruntime.contract.ContractValidator
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Shared parts of the suite's pacts (STK-40, CS-38): the CloudEvents envelope every message travels in, as
 * outbox-and-queue writes it, and the check that a message's `data` validates against its pinned platform-contracts
 * payload schema (the files product-runtime stages from the bundle, served here under `contracts/`).
 */
object Contracts {
    const val PRODUCT_RUNTIME = "product-runtime"
    const val SERVICE_RUNTIME = "service-runtime"
    const val CONFIG_RESOLVER = "config-resolver"
    const val TENANCY_SVC = "tenancy-svc"
    const val ACCESS_SVC = "access-svc"

    const val TENANT = "0a000000-0000-0000-0000-00000000000a"
    const val TIME = "2026-10-12T02:00:00Z"
    private const val INSTANT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$"
    const val DATE = "^\\d{4}-\\d{2}-\\d{2}$"

    private val mapper = ObjectMapper()
    private val validator = ContractValidator()

    /** JSON Schema 2020-12 with format assertions on (`date`, `date-time`), for the message payloads. */
    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }
    val envelopes = EnvelopeJson(mapper)

    /**
     * A message pact body: the envelope fields a consumer reads through outbox-and-queue's [EnvelopeJson] (exact
     * specversion and type, every other field by type or format), with the payload [data] under `data`.
     */
    fun envelope(
        type: String,
        source: String?,
        entity: Boolean,
        docSequence: Boolean,
        data: PactDslJsonBody.() -> Unit,
    ): PactDslJsonBody {
        val body =
            PactDslJsonBody()
                .stringValue("specversion", "1.0")
                .stringType("id", "7d0c1c55-2b1a-4c47-9d40-5f5f1b7f3c01")
                .stringValue("type", type)
                .stringType("subject", "agg-1")
                .stringMatcher("time", INSTANT, TIME)
                .stringValue("datacontenttype", "application/json")
                .uuid("tenantid", java.util.UUID.fromString(TENANT))
                .stringType("instanceid", "svc-1")
                .stringType("correlationid", "corr-1")
                .stringType("causationid", "cause-1")
        if (source == null) {
            body.stringMatcher("source", "^/gst/[a-z][a-z0-9-]*$", "/gst/tenancy-svc")
        } else {
            body.stringValue("source", source)
        }
        if (entity) body.stringType("entityref", "E-001")
        if (docSequence) body.integerType("docsequence", 1L)
        val payload = body.`object`("data")
        payload.data()
        return payload.closeObject() as PactDslJsonBody
    }

    /** The message's text, as the consumer receives it off the event log. */
    fun text(message: V4Interaction.AsynchronousMessage): String = message.contents.contents.valueAsString()

    /** Fails unless the message's `data` validates against [schema] (relative to `contracts/`). */
    fun assertPayloadConforms(
        schema: String,
        envelopeJson: String,
    ) {
        val data: JsonNode = mapper.readTree(envelopeJson).get("data")
        val text = checkNotNull(javaClass.getResource("/contracts/$schema")) { "no pinned contract $schema" }.readText()
        schemas
            .getSchema(text)
            .validate(data)
            .map { it.toString() }
            .shouldBeEmpty()
    }

    /** Fails unless [body] is a response [openapi]'s operation [path] declares for [status] (the HTTP pacts). */
    fun assertResponseConforms(
        openapi: String,
        path: String,
        status: Int,
        body: String,
    ) {
        validator
            .operation(openapi, "GET", path)
            .responseErrors(status, "application/json", mapper.readTree(body))
            .shouldBeEmpty()
    }
}
