package com.dynokore.platform.serviceruntime.identity

import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * One line of a contract resource (`<METHOD><TAB><path template><TAB>...`) with its [payload] (the callers of
 * `sync-edge-callers.tsv`, the property of `entity-ref-operations.tsv`).
 */
internal class OperationLine<P>(
    val method: String,
    val template: String,
    val payload: P,
) {
    val pattern: PathPattern = PathPatternParser().parse(template)

    /** The path segments; one containing `{` is templated. */
    val segments: List<String> = template.split('/')
    val concrete: Set<Int> = segments.indices.filterNot { '{' in segments[it] }.toSet()
}

/**
 * The one place that resolves which line serves a request (OpenAPI path matching: a concrete segment beats a
 * templated one; coordinator rulings INV-Q-8526 and INV-Q-8544).
 */
internal class OperationLines<P> private constructor(
    private val lines: List<OperationLine<P>>,
) {
    /** The line of the operation that serves [path] for [method], or null when no line matches. */
    fun serving(
        method: String,
        path: PathContainer,
    ): OperationLine<P>? =
        // Equal-specificity lines cannot both exist (start-up check), so the most concrete segments identify the line.
        lines.filter { it.method == method && it.pattern.matches(path) }.maxByOrNull { it.concrete.size }

    companion object {
        /**
         * One line per operation. Same method and same template up to variable names: payloads equal by [same] are
         * one line, different ones stop the start-up (the message says "different [what]"). Different templates that
         * can match one path (same length, every segment equal or one of the two templated) need one to be concrete
         * wherever the other is: otherwise neither is more specific and the start-up stops. A message names the
         * [resource], the method and the templates, never a payload.
         */
        fun <P> of(
            parsed: List<OperationLine<P>>,
            resource: String,
            what: String,
            same: (P, P) -> Boolean,
        ): OperationLines<P> {
            val kept = mutableListOf<OperationLine<P>>()
            for (line in parsed) {
                var duplicate = false
                for (other in kept.filter { it.method == line.method && it.segments.size == line.segments.size }) {
                    val differing =
                        line.segments.indices.filter { line.segments[it] != other.segments[it] }
                    val compatible = differing.all { it !in line.concrete || it !in other.concrete }
                    if (!compatible) continue
                    val sameShape = line.concrete == other.concrete
                    if (sameShape) {
                        check(same(line.payload, other.payload)) {
                            "$resource: ${line.method} ${line.template} and ${other.template}: " +
                                "the same operation with different $what"
                        }
                        duplicate = true
                    } else {
                        check(line.concrete.containsAll(other.concrete) || other.concrete.containsAll(line.concrete)) {
                            "$resource: ${line.method} ${line.template} and ${other.template} are equally specific"
                        }
                    }
                }
                if (!duplicate) kept += line
            }
            return OperationLines(kept)
        }
    }
}
