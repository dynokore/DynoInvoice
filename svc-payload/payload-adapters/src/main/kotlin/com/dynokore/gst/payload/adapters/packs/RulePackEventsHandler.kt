package com.dynokore.gst.payload.adapters.packs

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The consumer of `RulePackStaged` and `RulePackActivated` for payload-svc (event-carried state transfer,
 * INV-AC-23578).
 * One instance handles exactly one event type ([expectedType], exact string equality); any other type is a
 * [ContractViolation] before the payload is read. A staged pack of a type other than [MDE_PACK_TYPE] is ignored. An
 * activation of a version that is not staged throws [IllegalStateException] (inbox retry); nothing is invented.
 */
class RulePackEventsHandler(
    private val expectedType: String,
    private val replica: JooqMdePackReplica,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != expectedType) throw ContractViolation("expected $expectedType, was ${event.type}")
        val data = parse(event.dataJson)
        return if (expectedType == STAGED_TYPE) staged(data) else activated(data)
    }

    private fun staged(data: JsonNode): HandleOutcome {
        if (text(data, "packType") != MDE_PACK_TYPE) return HandleOutcome.IGNORED
        val version = text(data, "version")
        val contents =
            data.get("artefact")?.get("contents")?.takeUnless { it.isNull || it.isMissingNode }
                ?: throw ContractViolation("an MDE pack carries no artefact.contents")
        return when (replica.stage(version, json.writeValueAsString(contents))) {
            StageResult.STORED -> {
                HandleOutcome.APPLIED
            }

            StageResult.UNCHANGED -> {
                HandleOutcome.IGNORED
            }

            StageResult.CONFLICT -> {
                LoggerFactory
                    .getLogger(RulePackEventsHandler::class.java)
                    .error("staged MDE pack version {} came with other contents; the stored one is kept", version)
                throw ContractViolation("an MDE pack version is already staged with other contents")
            }
        }
    }

    private fun activated(data: JsonNode): HandleOutcome =
        if (replica.activate(text(data, "version"))) HandleOutcome.APPLIED else HandleOutcome.IGNORED

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("the payload member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }.also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }

    companion object {
        const val MDE_PACK_TYPE = "MDE"
        const val STAGED_TYPE = "com.dynokore.gst.platform.RulePackStaged.v1"
        const val ACTIVATED_TYPE = "com.dynokore.gst.platform.RulePackActivated.v1"
        const val STAGED_TOPIC = "gst.platform.rule-pack-staged.v1"
        const val ACTIVATED_TOPIC = "gst.platform.rule-pack-activated.v1"
    }
}
