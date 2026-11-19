package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.livePermissionKey
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import io.micrometer.core.instrument.MeterRegistry
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
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** One row of `META-INF/gst/high-risk-live-calls.tsv`: the contract's [operationId], the live call's [permission]. */
internal data class HighRiskLiveCall(
    val operationId: String,
    val permission: String,
)

/**
 * The live authorise of a high-risk operation (INV-T-9258; INV-REQ-1201 behaviour 7, owner answers INV-Q-9243 B and
 * INV-Q-9245 A). A request whose method and path match a row of `META-INF/gst/high-risk-live-calls.tsv` is decided by
 * one call to access-svc ([AuthorityReplica.decideLive]) whatever the replica lag: the replica answer and the
 * degraded-allow table are never consulted. A DENY is 403 (SRT-004), access-svc unreachable (or no entity, no tenant)
 * is 503 (SRT-013), for every principal kind; in both the handler is not called, so nothing is stored. The permission
 * key is [livePermissionKey] of the row's key and the principal kind; the entity is the path variable `entityRef`;
 * the tenant is the bound principal's. Every other request passes untouched. The operation is found by method and
 * path with [OperationLines.serving] (equality, never a prefix, and never a controller name).
 *
 * Counts each call in `high_risk_live_authorise_total` (`operation`, `result` in ALLOW, DENY, UNREACHABLE) and logs one
 * line with operation, principalKind and result, never a principal id or an entity value.
 */
internal class HighRiskLiveAuthoriseFilter(
    private val authority: AuthorityReplica,
    private val meters: MeterRegistry?,
    private val clock: Clock,
    parsed: List<OperationLine<HighRiskLiveCall>>,
) : Filter {
    private val log = LoggerFactory.getLogger(HighRiskLiveAuthoriseFilter::class.java)
    private val operations = OperationLines.of(parsed, RESOURCE, "permissions") { a, b -> a == b }

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        val path = PathContainer.parsePath(http.requestURI.removePrefix(http.contextPath.orEmpty()))
        val line = operations.serving(http.method, path)
        if (line == null) {
            chain.doFilter(request, response)
            return
        }
        val out = response as HttpServletResponse
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val actor = actorOf(http)
        if (actor == null) {
            refuse(out, Problems.unauthenticated(correlation))
            return
        }
        val entity =
            line.pattern
                .matchAndExtract(path)
                ?.uriVariables
                ?.get(ENTITY_VARIABLE)
        val tenant = actor.tenant
        if (entity.isNullOrBlank() || tenant == null) {
            record(line.payload, actor.principal.kind, UNREACHABLE)
            refuse(out, Problems.closed(ClosedErrorCode.SERVICE_NOT_READY, correlation))
            return
        }
        val kind = actor.principal.kind
        val decision =
            decide(
                AuthorizationRequest(tenant, actor.principal, entity, livePermissionKey(line.payload.permission, kind)),
            )
        when {
            decision.allowed -> {
                record(line.payload, kind, ALLOW)
                chain.doFilter(request, response)
            }

            decision.reason == DecisionReason.AUTHORITY_UNAVAILABLE -> {
                record(line.payload, kind, UNREACHABLE)
                refuse(out, Problems.closed(ClosedErrorCode.SERVICE_NOT_READY, correlation))
            }

            else -> {
                record(line.payload, kind, DENY)
                refuse(out, Problems.accessDenied(correlation))
            }
        }
    }

    private fun decide(request: AuthorizationRequest): AuthorityDecision =
        authority.decideLive(request, CurrentDeadline.current() ?: Deadline.after(BUDGET, clock))

    private class Actor(
        val principal: Principal,
        val tenant: TenantId?,
    )

    /** The principal an earlier filter bound: a verified machine token, else a resolved end user (a person). */
    private fun actorOf(http: HttpServletRequest): Actor? {
        (http.getAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE) as? Principal)?.let { return Actor(it, it.tenant) }
        val person = http.getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        return person?.let { Actor(Principal(it.actor, PrincipalKind.HUMAN), it.tenant) }
    }

    private fun record(
        call: HighRiskLiveCall,
        kind: PrincipalKind,
        result: String,
    ) {
        meters?.counter(METER_NAME, "operation", call.operationId, "result", result)?.increment()
        log.info("high_risk_live_authorise operation={} principalKind={} result={}", call.operationId, kind, result)
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
        const val RESOURCE = "META-INF/gst/high-risk-live-calls.tsv"
        const val HEADER = "operationId\tmethod\tpath\tpermission"
        const val METER_NAME = "high_risk_live_authorise_total"
        const val ALLOW = "ALLOW"
        const val DENY = "DENY"
        const val UNREACHABLE = "UNREACHABLE"
        private const val ENTITY_VARIABLE = "entityRef"
        private val BUDGET: Duration = Duration.ofSeconds(2)
        private val OPERATION_ID = Regex("^[A-Za-z][A-Za-z0-9_]*$")
        private val METHOD = Regex("^[A-Z]{3,10}$")
        private val TEMPLATE = Regex("^/[^\\s\\p{Cntrl}]*$")
        private val PERMISSION = Regex("^[^\\s\\p{Cntrl}]+$")

        /**
         * The rows of every classpath resource [RESOURCE]. No resource, or only the header, gives no row. A malformed,
         * unsorted or repeated row stops the start-up; the message names the resource and the line number, never the
         * line.
         */
        fun rows(loader: ClassLoader): List<OperationLine<HighRiskLiveCall>> {
            val seen = mutableMapOf<String, String>()
            return loader.getResources(RESOURCE).toList().flatMap { url ->
                rowsOf(url)
                    .onEach { (where, line) ->
                        val earlier = seen.putIfAbsent(line.payload.operationId, where)
                        check(earlier == null) { "$where: repeats the operationId of $earlier" }
                    }.map { it.second }
            }
        }

        private fun rowsOf(url: URL): List<Pair<String, OperationLine<HighRiskLiveCall>>> {
            val text = url.openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            val lines = text.removeSuffix("\n").split('\n')
            check(lines.first() == HEADER) { "$RESOURCE $url line 1: the header row must be $HEADER" }
            var previous: String? = null
            return lines.drop(1).mapIndexed { index, line ->
                val where = "$RESOURCE $url line ${index + 2}"
                val parts = line.split('\t')
                check(parts.size == 4) { "$where: a line needs exactly 4 TAB-separated fields" }
                check(
                    OPERATION_ID.matches(parts[0]),
                ) { "$where: the operationId must be letters, digits and underscores" }
                check(METHOD.matches(parts[1])) { "$where: the method must be upper-case letters" }
                check(
                    TEMPLATE.matches(parts[2]),
                ) { "$where: the path must start with / and hold no space or control character" }
                check(
                    PERMISSION.matches(parts[3]),
                ) { "$where: the permission must be one token with no space or control character" }
                check(runCatching { livePermissionKey(parts[3], PrincipalKind.HUMAN) }.isSuccess) {
                    "$where: the person key of the permission is empty"
                }
                check(previous.let { it == null || it < parts[0] }) { "$where: the rows must be sorted by operationId" }
                previous = parts[0]
                where to OperationLine(parts[1], parts[2], HighRiskLiveCall(parts[0], parts[3]))
            }
        }
    }
}
