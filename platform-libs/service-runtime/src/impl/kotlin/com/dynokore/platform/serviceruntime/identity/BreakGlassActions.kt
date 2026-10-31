package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.BreakGlassAction
import org.springframework.http.server.PathContainer
import java.nio.charset.StandardCharsets

/** Lines of a break-glass contract resource: every classpath resource of [resource], one `OperationLine` per line. */
private fun <P> breakGlassLines(
    loader: ClassLoader,
    resource: String,
    cells: Int,
    payload: (cell: String, where: String) -> P,
): List<OperationLine<P>> =
    loader.getResources(resource).toList().flatMap { url ->
        val text = url.openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        text.removeSuffix("\n").takeIf { it.isNotEmpty() }?.split('\n').orEmpty().mapIndexed { index, line ->
            val where = "$resource $url line ${index + 1}"
            val parts = line.split('\t')
            check(parts.size == cells) { "$where: a line needs exactly $cells TAB-separated cells" }
            check(METHOD.matches(parts[0])) { "$where: the method must be upper-case letters" }
            check(
                TEMPLATE.matches(parts[1]),
            ) { "$where: the path must start with / and hold no space or control character" }
            OperationLine(parts[0], parts[1], payload(parts.getOrElse(2) { "" }, where))
        }
    }

private val METHOD = Regex("^[A-Z]{3,7}$")
private val TEMPLATE = Regex("^/[^\\s\\p{Cntrl}]*$")

/** The template with variable names erased, so `/a/{x}` and `/a/{y}` are one operation. */
private fun shape(template: String): String = template.replace(Regex("\\{[^}]*}"), "{}")

/**
 * The operations marked as break-glass actions (INV-Q-9119 A), read from every classpath resource
 * `META-INF/gst/break-glass-actions.tsv`: `<METHOD><TAB><path template><TAB><action>`, the action one of `VIEW`,
 * `READINESS`, `DRAIN`, `REPLAY`. A malformed line, an unknown action or two lines for one operation with different
 * actions stop the start-up; a message names the resource and the line, never a payload. No resource gives an empty set.
 */
internal class BreakGlassActions private constructor(
    private val lines: List<OperationLine<BreakGlassAction>>,
) {
    private val operations = OperationLines.of(lines, RESOURCE, "actions") { a, b -> a == b }

    /** The line of the marked operation that serves [path] for [method], or null. */
    fun serving(
        method: String,
        path: PathContainer,
    ): OperationLine<BreakGlassAction>? = operations.serving(method, path)

    /** True when ([method], [template]) is a marked operation up to variable names. */
    fun contains(
        method: String,
        template: String,
    ): Boolean = lines.any { it.method == method && shape(it.template) == shape(template) }

    companion object {
        const val RESOURCE = "META-INF/gst/break-glass-actions.tsv"

        fun from(loader: ClassLoader): BreakGlassActions =
            BreakGlassActions(
                breakGlassLines(loader, RESOURCE, 3) { cell, where ->
                    BreakGlassAction.entries.firstOrNull { it.name == cell }
                        ?: error("$where: the action must be one of ${BreakGlassAction.entries.joinToString()}")
                },
            )
    }
}

/**
 * The operations that stay callable by the approvers of an open session (owner answer INV-Q-9298 B: the five
 * lifecycle operations of access-svc), read from every classpath resource
 * `META-INF/gst/break-glass-lifecycle-operations.tsv`: `<METHOD><TAB><path template>`. A malformed line, a third cell,
 * or a line that is also a line of the marker resource stops the start-up; a message names the resource and the line,
 * never a payload. No resource gives an empty set.
 */
internal class BreakGlassLifecycleOperations private constructor(
    lines: List<OperationLine<Unit>>,
) {
    private val operations = OperationLines.of(lines, RESOURCE, "payloads") { _, _ -> true }

    /** The line of the lifecycle operation that serves [path] for [method], or null. */
    fun serving(
        method: String,
        path: PathContainer,
    ): OperationLine<Unit>? = operations.serving(method, path)

    companion object {
        const val RESOURCE = "META-INF/gst/break-glass-lifecycle-operations.tsv"

        fun from(
            loader: ClassLoader,
            marked: BreakGlassActions,
        ): BreakGlassLifecycleOperations {
            val lines = breakGlassLines(loader, RESOURCE, 2) { _, _ -> }
            lines.forEach {
                check(!marked.contains(it.method, it.template)) {
                    "$RESOURCE: ${it.method} ${it.template} is also a line of ${BreakGlassActions.RESOURCE}"
                }
            }
            return BreakGlassLifecycleOperations(lines)
        }
    }
}
