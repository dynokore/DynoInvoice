package com.dynokore.gst.reconciliation.syncedge

import org.springframework.beans.factory.config.YamlMapFactoryBean
import org.springframework.core.io.ByteArrayResource

/** One operation of the contract document that names its callers: its `x-sync-edge` and a request that is valid. */
data class Operation(
    val method: String,
    val template: String,
    val edge: List<String>,
    val body: String,
    val headers: Map<String, String>,
    val query: String,
    val pathValues: Map<String, String>,
) {
    val label get() = "$method $template"

    /** The text before `->` of each element. */
    val callerFields get() = edge.map { it.substringBefore("->") }
}

/** The operations of the document `gst/contracts/reconciliation/openapi.yaml` of the contract module. */
object ContractDocument {
    private const val RESOURCE = "gst/contracts/reconciliation/openapi.yaml"

    fun text(loader: ClassLoader): String =
        checkNotNull(loader.getResourceAsStream(RESOURCE)) {
            "the reconciliation-contract module has no $RESOURCE"
        }.use { String(it.readAllBytes(), Charsets.UTF_8) }

    fun operations(text: String): List<Operation> {
        val factory = YamlMapFactoryBean()
        factory.setResources(ByteArrayResource(text.toByteArray()))
        val document = checkNotNull(factory.getObject()) { "the contract document is empty" }
        val samples = SampleValues(document)
        return (document["paths"] as Map<*, *>).flatMap { (path, item) ->
            (item as Map<*, *>).mapNotNull { (method, operation) ->
                val edge = (operation as? Map<*, *>)?.get("x-sync-edge")
                edge?.let { operation(samples, method.toString(), path.toString(), operation, it) }
            }
        }
    }

    private fun operation(
        samples: SampleValues,
        method: String,
        path: String,
        operation: Map<*, *>,
        edge: Any,
    ): Operation {
        val elements = if (edge is List<*>) edge.map { it as String } else listOf(edge as String)
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
