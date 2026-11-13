package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.document.ReleaseMasterDataHold
import com.dynokore.gst.canonical.application.document.ReleaseResult
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The consumer of `gst.masterdata.master-data-resolved.v1` for canonical-svc (INV-REQ-479, rounds zq and zs): it
 * reads the optional `docUuids` member and releases exactly those documents through [ReleaseMasterDataHold], which
 * runs inside the inbox consumer's transaction (the release, its outbox row and the inbox row commit together).
 *
 * `docUuids` absent, null or empty: IGNORED, the use case is not called (the consumer never guesses documents).
 * `docUuids` present but not an array, or an element that is not a canonical UUID string: [ContractViolation]
 * (dead letter at once). No exception of the use case is caught: a failure rolls the record back and retries it.
 */
class MasterDataResolvedHandler(
    private val release: ReleaseMasterDataHold,
) : EventHandler {
    private val json = JsonMapper.builder().build()
    private val log = LoggerFactory.getLogger(MasterDataResolvedHandler::class.java)

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val docUuids = docUuids(data).orEmpty()
        if (docUuids.isEmpty()) return HandleOutcome.IGNORED
        val results = release.release(docUuids)
        val counts = results.values.groupingBy { it }.eachCount()
        log.info(
            "master data resolved: released {}, not-held {}, not-master-data {}, not-found {}",
            counts[ReleaseResult.RELEASED] ?: 0,
            counts[ReleaseResult.NOT_HELD] ?: 0,
            counts[ReleaseResult.NOT_MASTER_DATA] ?: 0,
            counts[ReleaseResult.NOT_FOUND] ?: 0,
        )
        return if (ReleaseResult.RELEASED in counts) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun docUuids(data: JsonNode): List<UUID>? {
        val node = data.get(MEMBER)
        if (node == null || node.isNull) return null
        if (!node.isArray) throw ContractViolation("$MEMBER is not an array")
        return buildList {
            for (element in node) {
                if (!element.isString || !UUID_TEXT.matches(element.asString())) {
                    throw ContractViolation("$MEMBER holds an element that is not a UUID")
                }
                add(UUID.fromString(element.asString()))
            }
        }
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val MEMBER = "docUuids"
        val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
