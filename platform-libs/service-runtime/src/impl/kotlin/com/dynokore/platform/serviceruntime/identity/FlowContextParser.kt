package com.dynokore.platform.serviceruntime.identity

import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Reads the verified payload of a flow context against `access/schemas/flow-context.schema.json`. Strict and closed:
 * anything the schema does not allow (a repeated property, a wrong type, an unknown entity kind, a `ref` on a
 * TENANT_WIDE entity or none on an ENTITY, an empty or over-long text, a lifetime over 900 seconds) gives null.
 */
internal object FlowContextParser {
    private val MAPPER = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private const val MAX_TEXT = 64

    fun parse(
        payload: ByteArray,
        compact: String,
    ): FlowContext? =
        try {
            read(MAPPER.readTree(payload), compact)
        } catch (_: RuntimeException) {
            null
        }

    private fun read(
        root: JsonNode,
        compact: String,
    ): FlowContext? {
        if (!root.isObject) return null
        val tenant = text(root, "tenant", MAX_TEXT)
        val principalKind = text(root, "principalKind", Int.MAX_VALUE)
        val callerService = text(root, "callerService", Int.MAX_VALUE)
        val flowId = text(root, "flowId", MAX_TEXT)
        val entity = entity(root.get("entity"))
        val iat = seconds(root, "iat")
        val exp = seconds(root, "exp")
        val complete =
            tenant != null && principalKind != null && callerService != null && flowId != null && entity != null &&
                iat != null && exp != null && exp > iat && exp - iat <= FlowContext.MAX_LIFETIME_SECONDS
        return if (complete) {
            FlowContext(
                tenant,
                entity,
                principalKind,
                callerService,
                flowId,
                iat,
                exp,
                compact,
            )
        } else {
            null
        }
    }

    private fun entity(node: JsonNode?): FlowEntity? {
        if (node == null || !node.isObject) return null
        val kind = text(node, "kind", Int.MAX_VALUE)
        return when (kind) {
            EntityKind.ENTITY.name -> text(node, "ref", MAX_TEXT)?.let(FlowEntity::entity)
            EntityKind.TENANT_WIDE.name -> if (node.has("ref")) null else FlowEntity.tenantWide()
            else -> null
        }
    }

    private fun text(
        node: JsonNode,
        name: String,
        max: Int,
    ): String? =
        node
            .get(name)
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeIf { it.length in 1..max }

    private fun seconds(
        node: JsonNode,
        name: String,
    ): Long? =
        node
            .get(name)
            ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.asLong()
            ?.takeIf { it >= 0 }
}
