package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * The payloads of the replay protocol (platform-contracts `service-runtime/schemas/replay-requested-payload` and
 * `replay-completed-payload`, INV-API-3654, INV-API-3655): read strictly, so a malformed request or marker is a
 * [ContractViolation]. Unknown fields are ignored (the schemas are additive, API-41).
 */
class ReplayJson(
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun writeRequest(request: ReplayRequest): String {
        val node = mapper.createObjectNode()
        node.put("replayId", request.replayId.toString())
        node.put("producer", request.producer)
        node.put("mode", request.mode.name)
        request.scope?.let { node.set("scope", mapper.readTree(writeScope(it))) }
        return mapper.writeValueAsString(node)
    }

    /** The JSON object of [scope] (kind and its fields), as ReplayRequested and rebuildConsumer carry it. */
    fun writeScope(scope: ReplayScope): String {
        val node = mapper.createObjectNode()
        node.put("kind", scope.kind)
        when (scope) {
            is ReplayScope.Tenant -> {
                node.put("tenantId", scope.tenant.toString())
            }

            is ReplayScope.Entity -> {
                node.put("tenantId", scope.tenant.toString())
                node.put("entityRef", scope.entity.value)
            }

            is ReplayScope.Period -> {
                node.put("from", scope.from.toString())
                node.put("to", scope.to.toString())
                scope.tenant?.let { node.put("tenantId", it.toString()) }
            }
        }
        return mapper.writeValueAsString(node)
    }

    fun readRequest(json: String): ReplayRequest =
        guarded {
            val node = mapper.readTree(json)
            val mode = ReplayMode.valueOf(text(node, "mode"))
            ReplayRequest(
                replayId = UUID.fromString(text(node, "replayId")),
                producer = text(node, "producer"),
                mode = mode,
                scope = node.get("scope")?.takeUnless { it.isNull }?.let { readScope(it) },
            )
        }

    fun readScope(json: String): ReplayScope = guarded { readScope(mapper.readTree(json)) }

    /** The replayId a ReplayCompleted answers. */
    fun readCompleted(json: String): UUID =
        guarded {
            val node = mapper.readTree(json)
            require(node.get("eventsReplayed")?.isIntegralNumber == true) { "eventsReplayed missing" }
            UUID.fromString(text(node, "replayId"))
        }

    private fun readScope(node: JsonNode): ReplayScope {
        val tenant =
            node
                .get("tenantId")
                ?.takeIf { it.isString }
                ?.asString()
                ?.let { TenantId.fromClaim(it) }
        return when (text(node, "kind")) {
            "TENANT" -> {
                ReplayScope.Tenant(requireNotNull(tenant) { "TENANT scope needs tenantId" })
            }

            "ENTITY" -> {
                ReplayScope.Entity(
                    requireNotNull(tenant) {
                        "ENTITY scope needs tenantId"
                    },
                    EntityRef(text(node, "entityRef")),
                )
            }

            "PERIOD" -> {
                ReplayScope.Period(Instant.parse(text(node, "from")), Instant.parse(text(node, "to")), tenant)
            }

            else -> {
                throw IllegalArgumentException("unknown scope kind")
            }
        }
    }

    private fun text(
        node: JsonNode,
        field: String,
    ): String = node.get(field)?.takeIf { it.isString }?.asString() ?: throw IllegalArgumentException("$field missing")

    private fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (failure: RuntimeException) {
            throw ContractViolation("malformed replay payload: ${failure.message}")
        }
}
