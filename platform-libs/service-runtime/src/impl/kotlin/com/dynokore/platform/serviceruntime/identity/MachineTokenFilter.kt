package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.Principal
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
import java.nio.charset.StandardCharsets
import java.util.UUID

/** One line of `META-INF/gst/machine-principal-operations.tsv`: the operation's scope and its additional principal (`-`: none). */
internal data class MachineOperation(
    val scope: String,
    val also: String?,
)

/**
 * Verifies the machine access token of a machine operation (coordinator rulings INV-Q-9028 A, INV-Q-9040 A, INV-Q-9032 A):
 * a request whose method and path match a line of `META-INF/gst/machine-principal-operations.tsv`
 * (`<METHOD><TAB><path template><TAB><scope><TAB><also>`, written by `gst.contract-server`) is verified here by
 * [MachineTokenVerifier] and never reaches the end-user resolver. A bad token is 401 SRT-003, a valid token of the
 * wrong scope or a certification token outside SANDBOX 403 SRT-004; the handler is then not called. A verified
 * [Principal] (kind MACHINE) is left in [PRINCIPAL_ATTRIBUTE], and its tenant is bound to [TenantContext] for the
 * request when it has one. Any other request, `/v10/...` and `/internal/v1/...` included, passes untouched.
 */
class MachineTokenFilter internal constructor(
    private val verifier: MachineTokenVerifier,
    private val tenants: ObjectProvider<TenantContext>,
    parsed: List<OperationLine<MachineOperation>>,
) : Filter {
    private val operations = OperationLines.of(parsed, RESOURCE, "scopes") { a, b -> a == b }

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        http.removeAttribute(PRINCIPAL_ATTRIBUTE)
        val path = PathContainer.parsePath(http.requestURI.removePrefix(http.contextPath.orEmpty()))
        val line = operations.serving(http.method, path)
        if (line == null) {
            chain.doFilter(request, response)
            return
        }
        val out = response as HttpServletResponse
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        when (val verdict = verifier.verify(http.getHeader(HttpHeaders.AUTHORIZATION), line.payload)) {
            MachineVerdict.BadToken -> {
                refuse(out, Problems.unauthenticated(correlation))
            }

            MachineVerdict.WrongScope -> {
                refuse(out, Problems.accessDenied(correlation))
            }

            is MachineVerdict.Verified -> {
                http.setAttribute(PRINCIPAL_ATTRIBUTE, verdict.principal)
                val tenant = verdict.principal.tenant
                if (tenant == null) {
                    chain.doFilter(request, response)
                } else {
                    tenants.getObject().bind(tenant, verdict.principal.id) { chain.doFilter(request, response) }
                }
            }
        }
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
        const val RESOURCE = "META-INF/gst/machine-principal-operations.tsv"

        /** The request attribute that holds the verified machine [Principal]; cleared first on every request. */
        const val PRINCIPAL_ATTRIBUTE = "com.dynokore.platform.serviceruntime.identity.MachineTokenFilter.principal"

        private val SCOPES = setOf("ingest", "filing-workflow", "reporting", "monitoring", "certification")

        /** Every resource of [RESOURCE] on the classpath merged; a malformed or repeated line stops the start-up. */
        internal fun from(
            loader: ClassLoader,
            verifier: MachineTokenVerifier,
            tenants: ObjectProvider<TenantContext>,
        ): MachineTokenFilter =
            MachineTokenFilter(verifier, tenants, loader.getResources(RESOURCE).toList().flatMap(::linesOf))

        private fun linesOf(url: java.net.URL): List<OperationLine<MachineOperation>> {
            val text = url.openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            return text.removeSuffix("\n").takeIf { it.isNotEmpty() }?.split('\n').orEmpty().map { line ->
                val origin = "$RESOURCE $url"
                val parts = line.split('\t')
                check(parts.size == 4) { "$origin: a line needs exactly 4 TAB-separated fields" }
                check(parts[0].isNotEmpty()) { "$origin: the method is empty" }
                check(parts[1].startsWith("/")) { "$origin: the path must start with /" }
                check(parts[2] in SCOPES) { "$origin: the scope must be one of $SCOPES" }
                check(
                    parts[3] == "-" || parts[3] == "certification",
                ) { "$origin: the additional principal must be - or certification" }
                OperationLine(parts[0], parts[1], MachineOperation(parts[2], parts[3].takeIf { it != "-" }))
            }
        }
    }
}
