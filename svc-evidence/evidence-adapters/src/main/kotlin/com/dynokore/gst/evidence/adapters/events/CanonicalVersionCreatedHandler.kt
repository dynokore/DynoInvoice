package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.chain.LocatorRecorder
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * The consumer of `gst.canonical.canonical-version-created.v1` (INV-Q-7213 A (1)): stores the locator of the version
 * and drains the records that arrived before it. It runs inside the inbox consumer's transaction, so the locator, the
 * appended records, the inbox row and the offset commit together (INV-REQ-1222).
 *
 * An event with no `entityref` is a poison record (every document belongs to an entity, INV-REQ-644): the inbox
 * consumer refuses such an envelope before the handler runs and dead-letters it at once. A payload that lacks a
 * required field is refused here with [ContractViolation], which goes to the dead letter the same way.
 */
class CanonicalVersionCreatedHandler(
    private val recorder: LocatorRecorder,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val locator =
            DocumentLocator(
                docUuid = uuid(data, "docUuid"),
                version = data.required("version").asInt(),
                entityRef = event.entity.value,
                periodRef = text(data, "prescribedPeriodRef"),
                transactionDate = date(data),
                traceReference = text(data, "traceReference"),
                documentNumber =
                    data
                        .path(
                            "canonicalDocument",
                        ).path("documentNumber")
                        .path("value")
                        .takeIf { it.isString }
                        ?.asString(),
            )
        return if (recorder.record(
                event.tenant,
                event.id.value,
                locator,
            )
        ) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun JsonNode.required(name: String): JsonNode =
        get(name)?.takeUnless { it.isNull } ?: throw ContractViolation("the payload has no $name")

    private fun text(
        data: JsonNode,
        name: String,
    ): String = data.required(name).asString().also { if (it.isBlank()) throw ContractViolation("$name is blank") }

    private fun uuid(
        data: JsonNode,
        name: String,
    ): UUID =
        runCatching {
            UUID.fromString(text(data, name))
        }.getOrElse { throw ContractViolation("$name is not a UUID", it) }

    private fun date(data: JsonNode): LocalDate =
        runCatching { LocalDate.parse(text(data, "transactionDate")) }
            .getOrElse { throw ContractViolation("transactionDate is not a date", it) }
}
