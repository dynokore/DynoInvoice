package com.dynokore.platform.serviceruntime.identity

import org.springframework.http.server.PathContainer
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The operations the contract marks `x-flow-context: required`, read from every classpath resource
 * `META-INF/gst/flow-context-operations.tsv` (INV-T-9255, INV-T-19978): the header line exactly
 * `operationId<TAB>method<TAB>path`, then one row per marked operation. A request or an outbound call is listed when
 * [OperationLines.serving] (the one matcher of this module: a concrete segment beats a templated one) returns a row for
 * its method and path. No resource lists nothing. A first line other than the header, a malformed row or a repeated
 * operationId stops the start-up; the message names the resource and the line number, never the line.
 */
class FlowContextOperations internal constructor(
    private val lines: OperationLines<String>,
) {
    /** The operation key ("METHOD template") of the row that serves [method] and [path], or null when none does. */
    fun serving(
        method: String,
        path: String,
    ): String? = lines.serving(method, PathContainer.parsePath(path))?.let { "${it.method} ${it.template}" }

    companion object {
        const val RESOURCE = "META-INF/gst/flow-context-operations.tsv"
        const val HEADER = "operationId\tmethod\tpath"
        private val OPERATION_ID = Regex("^[A-Za-z][A-Za-z0-9_]*$")
        private val METHOD = Regex("^[A-Z]{3,10}$")
        private val TEMPLATE = Regex("^/[^\\s\\p{Cntrl}]*$")

        val NONE: FlowContextOperations = of(emptyList())

        fun from(loader: ClassLoader): FlowContextOperations =
            of(
                loader.getResources(RESOURCE).toList().map { url ->
                    url to
                        text(url)
                },
            )

        /** [resources] are (origin, text) pairs of the resource, for tests. */
        fun of(resources: List<Pair<Any, String>>): FlowContextOperations {
            val ids = mutableMapOf<String, String>()
            val rows = mutableListOf<OperationLine<String>>()
            for ((origin, text) in resources) {
                val lines = text.removeSuffix("\n").split('\n')
                check(lines.first() == HEADER) { "$RESOURCE $origin line 1: the header row must be $HEADER" }
                for ((index, line) in lines.drop(1).withIndex()) {
                    val where = "$RESOURCE $origin line ${index + 2}"
                    val parts = line.split('\t')
                    check(parts.size == 3) { "$where: a line needs exactly 3 TAB-separated fields" }
                    check(
                        OPERATION_ID.matches(parts[0]),
                    ) { "$where: the operationId must be letters, digits and underscores" }
                    check(METHOD.matches(parts[1])) { "$where: the method must be upper-case letters" }
                    check(
                        TEMPLATE.matches(parts[2]),
                    ) { "$where: the path must start with / and hold no space or control character" }
                    val earlier = ids.putIfAbsent(parts[0], where)
                    check(earlier == null) { "$where: repeats the operationId of $earlier" }
                    rows += OperationLine(parts[1], parts[2], parts[0])
                }
            }
            return FlowContextOperations(OperationLines.of(rows, RESOURCE, "operations") { a, b -> a == b })
        }

        private fun text(url: URL): String = url.openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }
}
