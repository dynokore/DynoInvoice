package com.dynokore.gst.submission.syncedge

import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.replica.BreakGlassAction
import com.dynokore.platform.serviceruntime.replica.BreakGlassActionReporter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One call of the stub reporter. */
data class Report(
    val unlockRef: String,
    val action: BreakGlassAction,
    val target: String,
    val occurredAt: Instant,
)

/** The state the break-glass doubles share with the test: the reports, the clock and the ordinary denial switch. */
object BreakGlassDoubles {
    val reports = CopyOnWriteArrayList<Report>()
    val now = AtomicReference(Instant.EPOCH)

    /** The event log the service's consumer reads (the offsets never restart: the consumer's position is stored). */
    val log = CopyOnWriteArrayList<LogRecord>()
    private val offsets = AtomicLong()

    fun append(raw: String) {
        log += LogRecord(offsets.incrementAndGet(), raw)
    }

    /** True while the ordinary authorisation double denies P1 on `getReadiness` (case f). */
    @Volatile var denyReadiness = false

    fun reset(at: Instant) {
        reports.clear()
        log.clear()
        Doubles.reset()
        now.set(at)
        denyReadiness = false
    }
}

/**
 * Doubles for INV-AC-9344: end-user tokens of P1 in tenant T and of P1 in tenant T2, the stub
 * [BreakGlassActionReporter], a test clock, a stand-in for the ordinary authorisation of `getReadiness` that denies P1
 * on request (the controllers are stubs, so the service has no ordinary decision of its own yet) and an interceptor
 * that records each request that reaches a handler (the use case double). Outside the scanned packages.
 */
@Configuration(proxyBeanMethods = false)
class BreakGlassTestBeans(
    private val tenants: ObjectProvider<TenantContext>,
) : WebMvcConfigurer {
    @Bean
    @Primary
    fun testWorkloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            val value = token?.value
            if (value != null && value.startsWith("token-")) {
                WorkloadVerification.Verified("sa-" + value.removePrefix("token-"))
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }

    @Bean
    @Primary
    fun testEndUserResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            Doubles.resolverCalls.incrementAndGet()
            when (authorization) {
                "Bearer $P1_TOKEN" -> EndUserPrincipal(P1, TenantId.of(TENANT_T), emptySet())
                "Bearer $P1_T2_TOKEN" -> EndUserPrincipal(P1, TenantId.of(TENANT_T2), emptySet())
                else -> null
            }
        }

    @Bean
    @Primary
    fun testEventLogReader(): EventLogReader =
        object : EventLogReader {
            override fun read(
                topic: String,
                afterOffset: Long,
                limit: Int,
            ): List<LogRecord> = BreakGlassDoubles.log.filter { it.offset > afterOffset }.take(limit)
        }

    @Bean
    fun stubBreakGlassActionReporter(): BreakGlassActionReporter =
        BreakGlassActionReporter { unlockRef, action, target, occurredAt ->
            BreakGlassDoubles.reports += Report(unlockRef, action, target, occurredAt)
        }

    @Bean
    @Primary
    fun testClock(): Clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?): Clock = this

            override fun instant(): Instant = BreakGlassDoubles.now.get()
        }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(
            object : HandlerInterceptor {
                override fun preHandle(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    handler: Any,
                ): Boolean {
                    val readiness = request.method == "GET" && request.requestURI.endsWith("/readiness")
                    if (BreakGlassDoubles.denyReadiness && readiness) {
                        response.status = DENIED
                        response.writer.write(ORDINARY_DENIAL)
                        return false
                    }
                    return true
                }
            },
        )
        registry.addInterceptor(
            object : HandlerInterceptor {
                override fun preHandle(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    handler: Any,
                ): Boolean {
                    val context = tenants.getObject()
                    val bound = runCatching { context.current().toString() to context.currentActor() }.getOrNull()
                    Doubles.reached += Reached(request.method + " " + request.requestURI, bound?.first, bound?.second)
                    return true
                }
            },
        )
    }

    companion object {
        const val P1_TOKEN = "p1-token"
        const val P1_T2_TOKEN = "p1-t2-token"
        const val P1 = "P1"
        const val TENANT_T = "0b8f5f0e-52a4-4d0a-9d4e-3f1f6a1c7e11"
        const val TENANT_T2 = "5c2d7a10-3b6e-4f58-8a41-9d0e6b7c2f33"
        const val DENIED = 403
        const val ORDINARY_DENIAL = "ordinary-authorisation-denied"
        val E: Instant = Instant.parse("2026-11-06T12:00:00Z")
        val BEFORE_E: Instant = E.minus(Duration.ofHours(1))
    }
}
