package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID

/**
 * Verifies the signed flow context of a system-flow call (owner answer on INV-Q-9241, option B; API-25; INV-REQ-1202;
 * INV-AC-8251), immediately after [SyncEdgeCallerFilter]. For an operation [FlowContextOperations] lists (matched by
 * method and path with [OperationLines.serving]; any other request is untouched) the request is refused with 403
 * SRT-004, the chain not called and nothing stored, unless the one `X-Flow-Context` header holds a context that
 *  1. verifies as a JWS against [FlowContextKeys] (no keys: refused),
 *  2. is inside `iat` (inclusive) and `exp` (exclusive) at the injected clock,
 *  3. agrees on the tenant with the token that authenticated the request, by string equality: a person token
 *     (`Authorization`, resolved by [EndUserPrincipalResolver]) must carry a tenant and the context `tenant` must equal
 *     it, and that verified tenant is bound; a request with no person token takes its tenant from the signed context
 *     only (a canonical lower-case UUID that is not the platform tenant), never from an unsigned value,
 *  4. has a `callerService` equal to the verified [ServiceCaller] of this request.
 * The tenant is bound to [TenantContext] for the length of the request, the context is the thread's
 * [FlowContext.current] (propagated by [FlowContextPropagationInterceptor]), and [FlowContextRefusedException] thrown
 * by a handler (`requireEntity`) is answered 403 SRT-004. One structured line per call is logged, accepted or refused;
 * it never holds the signed form. A person token that does not resolve is 401 SRT-003, an unreachable issuer 503 SRT-011.
 */
class FlowContextFilter internal constructor(
    private val operations: FlowContextOperations,
    private val keys: ObjectProvider<FlowContextKeys>,
    private val clock: ObjectProvider<Clock>,
    private val tenants: ObjectProvider<TenantContext>,
    private val resolvers: ObjectProvider<EndUserPrincipalResolver>,
) : Filter {
    private val log = LoggerFactory.getLogger(FlowContextFilter::class.java)

    private class Refused(
        val result: String,
        val context: FlowContext? = null,
        val status: StoredResponse? = null,
    ) : RuntimeException(result, null, false, false)

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        val path = http.requestURI.removePrefix(http.contextPath.orEmpty())
        val operation = operations.serving(http.method, path)
        if (operation == null) {
            chain.doFilter(request, response)
            return
        }
        val out = response as HttpServletResponse
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val caller = http.getAttribute(ServiceCaller.REQUEST_ATTRIBUTE) as? String
        var context: FlowContext? = null
        var result = RESULT_ACCEPTED
        try {
            val verified = verify(http, caller)
            context = verified.context
            run(verified, request, response, chain)
        } catch (e: Refused) {
            context = e.context
            result = e.result
            refuse(out, e.status ?: Problems.accessDenied(correlation))
        } catch (e: FlowContextRefusedException) {
            result = "REFUSED_${e.reason.uppercase()}"
            refuse(out, Problems.accessDenied(correlation))
        } catch (e: Exception) {
            val refusal = refusalIn(e)
            if (refusal == null) {
                result = RESULT_FAILED
                throw e
            }
            result = "REFUSED_${refusal.reason.uppercase()}"
            refuse(out, Problems.accessDenied(correlation))
        } finally {
            log.info(line(caller, context, operation, result))
        }
    }

    private class Verified(
        val context: FlowContext,
        val tenant: TenantId,
        val actor: String,
    )

    /** Steps 1 to 4; throws [Refused] for each failure, carrying the context once it has been read. */
    private fun verify(
        http: HttpServletRequest,
        caller: String?,
    ): Verified {
        val headers = http.getHeaders(FlowContext.HEADER_NAME)?.toList().orEmpty()
        if (headers.size != 1 || headers[0].isBlank()) throw Refused("REFUSED_NO_CONTEXT")
        val compact = headers[0]
        val payload =
            try {
                keys.ifAvailable?.verify(compact)
            } catch (_: RuntimeException) {
                null
            } ?: throw Refused("REFUSED_UNVERIFIED")
        val context = FlowContextParser.parse(payload, compact) ?: throw Refused("REFUSED_UNVERIFIED")
        val now = clock.getIfAvailable { Clock.systemUTC() }.instant().epochSecond
        if (now < context.issuedAt || now >= context.expiresAt) throw Refused("REFUSED_EXPIRED", context)
        if (caller == null || caller != context.callerService) throw Refused("REFUSED_CALLER", context)
        val authorization = http.getHeader(HttpHeaders.AUTHORIZATION)
        if (authorization == null) {
            val tenant = canonicalTenant(context.tenant) ?: throw Refused("REFUSED_TENANT", context)
            val actor = workloadActor(caller) ?: throw Refused("REFUSED_CALLER", context)
            return Verified(context, tenant, actor)
        }
        val principal =
            try {
                resolvers.ifAvailable?.resolve(authorization)
            } catch (_: TokenIssuerUnavailableException) {
                throw Refused(
                    "REFUSED_ISSUER_UNAVAILABLE",
                    context,
                    Problems.closed(ClosedErrorCode.ISSUER_UNAVAILABLE, correlationId()),
                )
            }
        if (principal == null) {
            throw Refused("REFUSED_PRINCIPAL", context, Problems.unauthenticated(correlationId()))
        }
        val tenant = principal.tenant
        if (tenant == null || tenant.toString() != context.tenant) throw Refused("REFUSED_TENANT", context)
        return Verified(context, tenant, principal.actor)
    }

    private fun run(
        verified: Verified,
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        tenants.getObject().bind(verified.tenant, verified.actor) {
            FlowContext.runWith(verified.context) { chain.doFilter(request, response) }
        }
    }

    private fun canonicalTenant(value: String): TenantId? =
        try {
            TenantId(UUID.fromString(value)).takeIf { it.toString() == value && !it.isPlatform }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun workloadActor(service: String): String? =
        try {
            WorkloadPrincipal(service).value
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun refusalIn(error: Throwable): FlowContextRefusedException? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < MAX_CAUSES) {
            if (current is FlowContextRefusedException) return current
            current = current.cause
        }
        return null
    }

    private fun correlationId(): String =
        CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"

    private fun refuse(
        response: HttpServletResponse,
        answer: StoredResponse,
    ) {
        if (response.isCommitted) return
        response.resetBuffer()
        response.status = answer.status
        response.contentType = Problems.CONTENT_TYPE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }

    private fun line(
        caller: String?,
        context: FlowContext?,
        operation: String,
        result: String,
    ): String =
        listOf(
            "callerService" to (caller ?: ""),
            "flowId" to (context?.flowId ?: ""),
            "tenant" to (context?.tenant ?: ""),
            "entityKind" to (context?.entity?.kind?.name ?: ""),
            "entityRef" to (context?.entity?.ref ?: ""),
            "principalKind" to (context?.principalKind ?: ""),
            "operation" to operation,
            "result" to result,
        ).joinToString(" ", prefix = "flow_context ") { (key, value) -> "$key=${quoted(value)}" }

    private fun quoted(value: String): String =
        buildString {
            append('"')
            for (c in value) {
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c.isISOControl() -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    companion object {
        const val RESULT_ACCEPTED = "ACCEPTED"
        private const val RESULT_FAILED = "FAILED"
        private const val MAX_CAUSES = 16
    }
}
