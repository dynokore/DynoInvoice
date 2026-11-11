package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.OffNetworkPrefixSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The `offNetworkEndpoint` section of the pack is present but its prefix is blank or not a string. */
class OffNetworkPrefixParseException(
    message: String,
) : RuntimeException(message)

/**
 * The off-network prefix of one MDE pack version (INV-Q-7189 A), read from the pack's `artefact.contents` JSON.
 * Created per build from the active pack version; no cache and no static state, so a new pack version is read by the
 * next build (INV-AC-1617). An absent section gives null; a blank or non-string prefix is a typed parse failure.
 */
class MdePackOffNetworkPrefix(
    private val contentsJson: String,
) : OffNetworkPrefixSource {
    override fun prefix(): String? {
        val section = JsonMapper().readTree(contentsJson).path("offNetworkEndpoint")
        if (section.isMissingNode) return null
        return text(section.path("prefix"))
    }

    private fun text(node: JsonNode): String {
        if (!node.isString || node.stringValue().isBlank()) {
            throw OffNetworkPrefixParseException("offNetworkEndpoint.prefix must be a non-blank string")
        }
        return node.stringValue()
    }
}
