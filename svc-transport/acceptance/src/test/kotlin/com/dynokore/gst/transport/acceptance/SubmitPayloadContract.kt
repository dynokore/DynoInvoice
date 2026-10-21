package com.dynokore.gst.transport.acceptance

import org.yaml.snakeyaml.Yaml

/**
 * The shape of the `submitPayload` operation of the transport contract (INV-REQ-795, INV-REQ-539; INV-AC-2031,
 * INV-AC-7857), deny by default: every property name reachable from the request body and from the 202 response must
 * be in a reviewed allowlist, so an AP credential, envelope element, batch field or AP error code, however it is
 * spelled, is a finding without the check knowing its name. The outcome enum must be exactly the three outcomes.
 *
 * Allowlist, one line each: the request is a Doc UUID, a version and a payload (`payload`); the response repeats the
 * Doc UUID and version, names the outcome, and carries rejection reasons (`reasons` with `code`, `field` and
 * `message`, the internal failure classes of SUB-nnn codes).
 */
object SubmitPayloadContract {
    const val OPERATION = "submitPayload"
    val REQUEST_ALLOWED = setOf("docUuid", "version", "payload")
    val RESPONSE_ALLOWED = setOf("docUuid", "version", "outcome", "reasons", "code", "field", "message")
    val OUTCOMES = listOf("ACCEPTED", "REJECTED", "TRANSIENT_FAILURE")

    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (value as? Map<String, Any?>).orEmpty()
    }

    private fun resolve(
        root: Map<String, Any?>,
        node: Map<String, Any?>,
    ): Map<String, Any?> {
        val ref = node["\$ref"] as? String ?: return node
        require(ref.startsWith("#/")) { "only local references are followed: $ref" }
        var current: Map<String, Any?> = root
        ref.removePrefix("#/").split('/').forEach { current = map(current[it]) }
        return resolve(root, current)
    }

    /** Property names reachable from [schema], following references, items, and the composition keywords. */
    private fun names(
        root: Map<String, Any?>,
        schema: Map<String, Any?>,
        seen: MutableSet<String> = mutableSetOf(),
    ): Set<String> {
        val ref = schema["\$ref"] as? String
        if (schema.isEmpty() || (ref != null && !seen.add(ref))) return emptySet()
        val node = resolve(root, schema)
        val found = mutableSetOf<String>()
        map(node["properties"]).forEach { (name, child) ->
            found += name
            found += names(root, map(child), seen)
        }
        found += names(root, map(node["items"]), seen)
        listOf("allOf", "oneOf", "anyOf").forEach { key ->
            (node[key] as? List<*>)?.forEach { found += names(root, map(it), seen) }
        }
        if (node["additionalProperties"] is Map<*, *>) found += names(root, map(node["additionalProperties"]), seen)
        return found
    }

    private fun operation(root: Map<String, Any?>): Map<String, Any?>? =
        map(root["paths"]).values.firstNotNullOfOrNull { item ->
            map(item).values.map { map(it) }.firstOrNull { it["operationId"] == OPERATION }
        }

    private fun body(
        root: Map<String, Any?>,
        content: Map<String, Any?>,
    ): Map<String, Any?>? =
        map(map(content["application/json"])["schema"])
            .takeIf {
                it.isNotEmpty()
            }?.let { resolve(root, it) }

    fun findings(openapi: String): List<String> {
        val root = map(Yaml().load<Any?>(openapi))
        val op = operation(root) ?: return listOf("operation $OPERATION is not in the contract")
        val findings = mutableListOf<String>()
        val requestSchema = map(map(op["requestBody"])["content"]).let { body(root, it) }
        if (requestSchema == null) {
            findings += "$OPERATION has no JSON request body"
        } else {
            (names(root, requestSchema) - REQUEST_ALLOWED).sorted().forEach {
                findings += "$OPERATION request: property '$it' is not a Doc UUID, version or payload field"
            }
        }
        val responseSchema = map(map(map(op["responses"])["202"])["content"]).let { body(root, it) }
        if (responseSchema == null) {
            findings += "$OPERATION has no JSON 202 response"
        } else {
            (names(root, responseSchema) - RESPONSE_ALLOWED).sorted().forEach {
                findings += "$OPERATION response: property '$it' is not part of the three-outcome answer"
            }
            val outcome = map(map(resolve(root, responseSchema)["properties"])["outcome"])
            if (outcome["enum"] !=
                OUTCOMES
            ) {
                findings += "$OPERATION response: outcome enum is ${outcome["enum"]}, not $OUTCOMES"
            }
        }
        return findings
    }
}
