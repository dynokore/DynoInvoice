package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Binds the verified end-user principal for the request (INV-REQ-1202 behavior 4; coordinator ruling INV-Q-8187):
 * the tenant (`tenant_id`) and the actor (`sub`) of the token go to [TenantContext] for the whole request, so a
 * generated controller runs with them bound and nothing is bound after the response. A principal without a tenant
 * (a platform operator) reaches the handler with nothing bound; the platform adapter binds the platform tenant
 * there, never this filter. A missing or refused token is 401 SRT-003 and an unreachable issuer 503 SRT-011, the
 * handler not called, as the command servlets answer.
 *
 * An operation listed in [UntouchedOperations] (workload-only, or marked `signed-link` or `service-job`, owner
 * answer INV-Q-8201) passes untouched: no token is read, nothing is bound. Every other request, one absent from the
 * contract resources included, is an end-user operation (fail closed).
 */
class EndUserPrincipalBindingFilter(
    private val resolvers: ObjectProvider<EndUserPrincipalResolver>,
    private val tenants: ObjectProvider<TenantContext>,
    private val untouched: UntouchedOperations,
) : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        http.removeAttribute(PRINCIPAL_ATTRIBUTE)
        if (untouched.matches(http.method, http.requestURI.removePrefix(http.contextPath.orEmpty()))) {
            chain.doFilter(request, response)
            return
        }
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val principal =
            try {
                http.getHeader(HttpHeaders.AUTHORIZATION)?.let { resolvers.ifAvailable?.resolve(it) }
            } catch (_: TokenIssuerUnavailableException) {
                refuse(
                    response as HttpServletResponse,
                    Problems.closed(ClosedErrorCode.ISSUER_UNAVAILABLE, correlation),
                )
                return
            }
        val tenant = principal?.tenant
        if (principal != null) http.setAttribute(PRINCIPAL_ATTRIBUTE, principal)
        when {
            principal == null -> refuse(response as HttpServletResponse, Problems.unauthenticated(correlation))
            tenant == null -> chain.doFilter(request, response)
            else -> tenants.getObject().bind(tenant, principal.actor) { chain.doFilter(request, response) }
        }
    }

    companion object {
        /** The request attribute that holds the resolved [EndUserPrincipal] for a later filter; cleared first on every request. */
        const val PRINCIPAL_ATTRIBUTE =
            "com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter.principal"
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
}

/**
 * The operations [EndUserPrincipalBindingFilter] leaves alone, read from the contract resources of INV-T-8197
 * (`META-INF/gst/workload-only-operations.tsv`: `<METHOD><TAB><path template>`; `META-INF/gst/caller-auth-operations.tsv`:
 * `<METHOD><TAB><path template><TAB>signed-link|service-job`), every resource of those two exact names on the
 * classpath merged. A malformed line stops the start-up. No resource gives an empty set.
 */
class UntouchedOperations private constructor(
    private val operations: List<Pair<String, PathPattern>>,
) {
    fun matches(
        method: String,
        path: String,
    ): Boolean {
        val container = PathContainer.parsePath(path)
        return operations.any { (listed, template) -> listed == method && template.matches(container) }
    }

    companion object {
        const val WORKLOAD_ONLY_RESOURCE = "META-INF/gst/workload-only-operations.tsv"
        const val CALLER_AUTH_RESOURCE = "META-INF/gst/caller-auth-operations.tsv"
        private val CALLER_AUTH_VALUES = setOf("signed-link", "service-job")

        fun from(loader: ClassLoader): UntouchedOperations =
            of(
                lines(loader, WORKLOAD_ONLY_RESOURCE).map { parse(it, 2, null) } +
                    lines(loader, CALLER_AUTH_RESOURCE).map { parse(it, 3, CALLER_AUTH_VALUES) },
            )

        /** [operations] are (method, path template) pairs, already validated. */
        fun of(operations: List<Pair<String, String>>): UntouchedOperations {
            val parser = PathPatternParser() // Spring 7 has no optional trailing separator; the test pins it
            return UntouchedOperations(operations.map { (method, template) -> method to parser.parse(template) })
        }

        /** Each line with its origin; one final line break is not a line, an empty resource has none, any other empty line is malformed. */
        private fun lines(
            loader: ClassLoader,
            name: String,
        ): List<Pair<String, String>> =
            loader.getResources(name).toList().flatMap { url ->
                url.readText().removeSuffix("\n").takeIf { it.isNotEmpty() }?.split('\n').orEmpty().map {
                    "$name $url" to
                        it
                }
            }

        private fun parse(
            line: Pair<String, String>,
            fields: Int,
            values: Set<String>?,
        ): Pair<String, String> {
            val parts = line.second.split('\t')
            check(parts.size == fields) { "${line.first}: a line needs exactly $fields TAB-separated fields" }
            check(parts[0].isNotEmpty()) { "${line.first}: the method is empty" }
            check(parts[1].startsWith("/")) { "${line.first}: the path must start with /" }
            check(
                values == null || parts[2] in values,
            ) { "${line.first}: the caller-auth value must be one of $values" }
            return parts[0] to parts[1]
        }

        private fun URL.readText(): String = openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }
}
