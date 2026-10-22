package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.server.PathContainer
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** The callers a line of `sync-edge-callers.tsv` allows. */
sealed interface EdgeCallers {
    /** `any`: every verified caller, mapped or not. */
    data object Any : EdgeCallers

    /** `any-except:<svc>`: every mapped caller but [service]. */
    data class AnyExcept(
        val service: String,
    ) : EdgeCallers

    /** A named list of services. */
    data class Named(
        val services: List<String>,
    ) : EdgeCallers
}

/**
 * Refuses, with 403 SRT-004 and the chain not called, a request under `/internal/v1/` whose captured
 * [ServiceCaller] is not one the operation's `x-sync-edge` allows (API-25; INV-AC-8251, INV-AC-8455). The allowed
 * callers are read from every classpath resource `META-INF/gst/sync-edge-callers.tsv` generated from the contract:
 * `<METHOD><TAB><path template><TAB><callers>`, callers a comma-separated list of service names, or `any`, or
 * `any-except:<svc>`. A malformed line stops the start-up. An `/internal/v1/` operation with no line, or whose caller
 * is not mapped (null), is refused unless its line is `any`; a request outside `/internal/v1/` is not touched.
 * Runs after [WorkloadTokenAuthenticationFilter], which has already refused a request with no valid token (401).
 * When several lines match a request, the one operation that serves it decides, by its line only (OpenAPI path
 * matching: a concrete segment beats a templated one). Two lines of one method whose templates are equally specific
 * but differ, or that name the same operation with different callers, stop the start-up; identical lines count as one.
 */
class SyncEdgeCallerFilter private constructor(
    parsed: List<OperationLine<EdgeCallers>>,
) : Filter {
    private val lines = OperationLines.of(parsed, RESOURCE, "callers") { a, b -> same(a, b) }

    private val log = LoggerFactory.getLogger(SyncEdgeCallerFilter::class.java)

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        val path = http.requestURI.removePrefix(http.contextPath.orEmpty())
        if (!path.startsWith(INTERNAL_PREFIX)) {
            chain.doFilter(request, response)
            return
        }
        val caller = http.getAttribute(ServiceCaller.REQUEST_ATTRIBUTE) as? String
        val container = PathContainer.parsePath(path)
        val serving = lines.serving(http.method, container)
        if (serving != null && allows(serving.payload, caller)) {
            chain.doFilter(request, response)
            return
        }
        val expected = serving?.let { describe(it.payload) } ?: "no line"
        log.warn("sync-edge caller refused: {} {} expects {}", http.method, path, expected)
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        refuse(response as HttpServletResponse, Problems.accessDenied(correlation))
    }

    private fun allows(
        callers: EdgeCallers,
        caller: String?,
    ): Boolean =
        when (callers) {
            EdgeCallers.Any -> true
            is EdgeCallers.AnyExcept -> caller != null && caller != callers.service
            is EdgeCallers.Named -> caller != null && caller in callers.services
        }

    private fun describe(callers: EdgeCallers): String =
        when (callers) {
            EdgeCallers.Any -> "any"
            is EdgeCallers.AnyExcept -> "any-except:${callers.service}"
            is EdgeCallers.Named -> callers.services.joinToString(",")
        }

    private fun refuse(
        response: HttpServletResponse,
        answer: StoredResponse,
    ) {
        response.status = answer.status
        response.contentType = Problems.CONTENT_TYPE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }

    companion object {
        const val RESOURCE = "META-INF/gst/sync-edge-callers.tsv"
        private const val INTERNAL_PREFIX = "/internal/v1/"
        private const val EXCEPT = "any-except:"
        private val SERVICE = WorkloadIdentityProperties.SERVICE_NAME

        private fun same(
            a: EdgeCallers,
            b: EdgeCallers,
        ): Boolean =
            if (a is EdgeCallers.Named && b is EdgeCallers.Named) a.services.toSet() == b.services.toSet() else a == b

        fun from(loader: ClassLoader): SyncEdgeCallerFilter =
            SyncEdgeCallerFilter(
                loader.getResources(RESOURCE).toList().flatMap { url ->
                    url.readText().removeSuffix("\n").takeIf { it.isNotEmpty() }?.split('\n').orEmpty().map {
                        parse("$RESOURCE $url", it)
                    }
                },
            )

        /** [lines] are the raw text lines of one resource, for tests. */
        fun of(lines: List<String>): SyncEdgeCallerFilter = SyncEdgeCallerFilter(lines.map { parse(RESOURCE, it) })

        private fun parse(
            origin: String,
            line: String,
        ): OperationLine<EdgeCallers> {
            val parts = line.split('\t')
            check(parts.size == 3) { "$origin: a line needs exactly 3 TAB-separated fields" }
            check(parts[0].isNotEmpty()) { "$origin: the method is empty" }
            check(parts[1].startsWith("/")) { "$origin: the path must start with /" }
            return OperationLine(parts[0], parts[1], callers(origin, parts[2]))
        }

        private fun callers(
            origin: String,
            field: String,
        ): EdgeCallers {
            if (field.startsWith(EXCEPT)) {
                val service = field.removePrefix(EXCEPT)
                check(SERVICE.matches(service)) { "$origin: an exclusion needs one valid service name" }
                return EdgeCallers.AnyExcept(service)
            }
            val names = field.split(',')
            check(names.none { it.isEmpty() }) { "$origin: an empty caller" }
            check(names.toSet().size == names.size) { "$origin: a repeated caller" }
            if ("any" in names) {
                check(names.size == 1) { "$origin: 'any' cannot be combined with another caller" }
                return EdgeCallers.Any
            }
            check(names.all { SERVICE.matches(it) }) { "$origin: a caller is not a valid service name" }
            return EdgeCallers.Named(names)
        }

        private fun URL.readText(): String = openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }
}
