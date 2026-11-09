package com.dynokore.gst.rules.served

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** A clock a test moves: the replica's lag is measured on it, never on the wall clock. */
class MovableClock(
    start: Instant = Instant.now(),
) : Clock() {
    private val now = AtomicReference(start)

    fun advance(by: Duration) {
        now.updateAndGet { it.plus(by) }
    }

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now.get()
}

/** What the replica's synchronous fallback to access-svc answers and was asked, set by the test. */
object ReplicaFallbackDouble {
    @Volatile var answer: FallbackResult<AuthorityVerdict> = FallbackResult.Unavailable
    val asked = CopyOnWriteArrayList<AuthorizationRequest>()

    fun reset() {
        answer = FallbackResult.Unavailable
        asked.clear()
    }
}

/** The replica fixture of the VP-39 decision cases, and the token resolver that carries tenant and entities. */
@TestConfiguration(proxyBeanMethods = false)
class PolicyFixtures {
    /** The token is `Bearer <user>|<tenant uuid>|<entity,entity>`; anything else is no valid token. */
    @Bean
    fun policyTokenResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { header ->
            val parts = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.split('|')
            parts?.takeIf { it.size == 3 }?.let { (user, tenant, entities) ->
                EndUserPrincipal(
                    user,
                    TenantId.of(tenant),
                    entities
                        .split(',')
                        .filter { it.isNotBlank() }
                        .map {
                            com.dynokore.platform.outboxandqueue
                                .EntityRef(it)
                        }.toSet(),
                )
            }
        }

    @Bean
    fun policyWorkloadVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token: WorkloadToken? ->
            val value = token?.value
            if (value != null && value.startsWith("wl-")) {
                WorkloadVerification.Verified(subjectOf(value.removePrefix("wl-")))
            } else {
                WorkloadVerification.Refused("no test token")
            }
        }

    /**
     * The one AuthorityReplica of the context, taking the place of service-runtime's `@ConditionalOnMissingBean`
     * bean: the production role map (permission key to role, from the catalogue), the movable clock, the
     * fallback double.
     * double.
     */
    @Bean
    fun authorityReplica(
        clock: MovableClock,
        roles: OperationRoleMap,
    ): AuthorityReplica =
        AuthorityReplica(clock, roles, fallback = { request, _ ->
            ReplicaFallbackDouble.asked += request
            ReplicaFallbackDouble.answer
        })

    @Bean
    fun movableClock(): MovableClock = MovableClock()
}
