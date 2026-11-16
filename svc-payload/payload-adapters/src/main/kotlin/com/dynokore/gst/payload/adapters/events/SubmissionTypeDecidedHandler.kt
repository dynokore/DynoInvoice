package com.dynokore.gst.payload.adapters.events

import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.application.build.BuildTrigger
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The consumer of `gst.submission.submission-type-decided.v1` for payload-svc: stores the decided type of the version
 * once and asks the [BuildTrigger] to enqueue the build. It runs in the inbox consumer's transaction. A payload that is
 * not JSON, or lacks or malforms docUuid, version, docsequence, submissionType or the optional connectionRef, is a
 * [ContractViolation].
 */
class SubmissionTypeDecidedHandler(
    private val decisions: JooqTypeDecisionStore,
    private val trigger: BuildTrigger,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val docUuid =
            runCatching { UUID.fromString(text(data, "docUuid")) }
                .getOrElse { throw ContractViolation("docUuid is not a UUID", it) }
        val version = positiveInt(data, "version")
        positiveInt(data, "docsequence")
        val type = text(data, "submissionType")
        if (type !in TYPES) throw ContractViolation("submissionType is not one of the four types")
        if (!decisions.store(docUuid, version, type, connectionRef(data))) return HandleOutcome.IGNORED
        trigger.onDecisionStored(docUuid, version)
        return HandleOutcome.APPLIED
    }

    /** The optional `connectionRef`: absent or null is null; anything but 1 to 40 characters of text is refused. */
    private fun connectionRef(data: JsonNode): String? {
        val node = data.get("connectionRef")?.takeUnless { it.isNull } ?: return null
        if (!node.isString || node.asString().isEmpty() || node.asString().length > MAX_CONNECTION_REF) {
            throw ContractViolation("connectionRef is not a non-empty text of at most $MAX_CONNECTION_REF characters")
        }
        return node.asString()
    }

    private fun positiveInt(
        data: JsonNode,
        name: String,
    ): Int {
        val node = data.get(name)?.takeUnless { it.isNull } ?: throw ContractViolation("the payload has no $name")
        return node.takeIf { it.isInt && it.asInt() >= 1 }?.asInt()
            ?: throw ContractViolation("$name is not an integer of 1 or more")
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)?.takeUnless { it.isNull } ?: throw ContractViolation("the payload has no $name")
        if (!node.isString) throw ContractViolation("$name is not text")
        return node.asString()
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val MAX_CONNECTION_REF = 40
        val TYPES = setOf("TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3")
    }
}
