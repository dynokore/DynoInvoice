package com.dynokore.gst.tenancy.syncedge

import org.springframework.beans.factory.config.YamlMapFactoryBean
import org.springframework.core.io.ByteArrayResource

/** One operation of the contract document: its markers and a request that is valid. */
data class Operation(
    val method: String,
    val template: String,
    val principal: String?,
    val callerAuth: String?,
    val edge: List<String>,
    val body: String,
    val headers: Map<String, String>,
    val query: String,
    val pathValues: Map<String, String>,
) {
    val label get() = "$method $template"

    /** The text before `->` of each element of `x-sync-edge`. */
    val callerFields get() = edge.map { it.substringBefore("->") }

    /** INV-AC-8260 class (a): the filter leaves the operation alone (owner answer INV-Q-8201). */
    val untouched get() = principal == "workload" || callerAuth == "signed-link" || callerAuth == "service-job"
}

/** The operations of the document `gst/contracts/tenancy/openapi.yaml` of the contract module. */
object ContractDocument {
    private const val RESOURCE = "gst/contracts/tenancy/openapi.yaml"

    fun text(loader: ClassLoader): String =
        checkNotNull(loader.getResourceAsStream(RESOURCE)) {
            "the tenancy-contract module has no $RESOURCE"
        }.use { String(it.readAllBytes(), Charsets.UTF_8) }

    fun operations(text: String): List<Operation> {
        val factory = YamlMapFactoryBean()
        factory.setResources(ByteArrayResource(text.toByteArray()))
        val document = checkNotNull(factory.getObject()) { "the contract document is empty" }
        val samples = SampleValues(document)
        return (document["paths"] as Map<*, *>).flatMap { (path, item) ->
            (item as Map<*, *>).mapNotNull { (method, operation) ->
                (operation as? Map<*, *>)?.takeIf { it["operationId"] != null }?.let {
                    operation(samples, method.toString(), path.toString(), it)
                }
            }
        }
    }

    private fun operation(
        samples: SampleValues,
        method: String,
        path: String,
        operation: Map<*, *>,
    ): Operation {
        val edge = operation["x-sync-edge"]
        val elements =
            when (edge) {
                null -> emptyList()
                is List<*> -> edge.map { it as String }
                else -> listOf(edge as String)
            }
        val parameters =
            (operation["parameters"] as? List<*>)
                .orEmpty()
                .filterIsInstance<Map<*, *>>()
                .map { parameter -> samples.resolve(parameter) }
                .filter { it["required"] == true }

        fun values(place: String) =
            parameters.filter { it["in"] == place }.associate {
                it["name"] as String to
                    value(samples, it)
            }
        return Operation(
            method = method.uppercase(),
            template = path,
            principal = operation["x-principal"]?.toString(),
            callerAuth = operation["x-caller-auth"]?.toString(),
            edge = elements,
            body = body(samples, operation),
            headers = values("header"),
            query = values("query").entries.joinToString("&") { "${it.key}=${it.value}" },
            pathValues = values("path"),
        )
    }

    private fun value(
        samples: SampleValues,
        parameter: Map<*, *>,
    ): String = samples.text(samples.sample(parameter["schema"]))

    /** The first example of the request body, else a value built from its schema. */
    private fun body(
        samples: SampleValues,
        operation: Map<*, *>,
    ): String {
        val content = (operation["requestBody"] as? Map<*, *>)?.get("content") as? Map<*, *>
        val json = content?.get("application/json") as? Map<*, *>
        val example = ((json?.get("examples") as? Map<*, *>)?.values?.firstOrNull() as? Map<*, *>)?.get("value")
        val built = example ?: json?.get("schema")?.let { samples.sample(it) }
        return built?.let(samples::json) ?: "{}"
    }
}
