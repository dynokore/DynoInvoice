package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Duration

/**
 * `service-runtime.replica.*`. The lag bound is INV-REQ-1201's one second; the
 * edges the two fallbacks run through are named [TENANCY_EDGE] and [ACCESS_EDGE]
 * under `service-runtime.resilience.edges` (500 ms timeout, one retry: the
 * defaults file sets both, INV-NOTE-3 §36.6).
 */
@ConfigurationProperties(prefix = "service-runtime.replica")
data class ReplicaProperties(
    val authorityLagBound: Duration = AuthorityReplica.DEFAULT_LAG_BOUND,
    /**
     * The producer topics `PolicyChanged` arrives on (one per owning service, INV-REQ-1241). The policy
     * replica's lag stays unknown until every one has reported its snapshot (INV-REQ-1200 sync gate).
     */
    val policyTopics: Set<String> = emptySet(),
)

const val TENANCY_EDGE = "tenancy-svc"
const val ACCESS_EDGE = "access-svc"

/**
 * Wires the three replicas (INV-REQ-1199). A service supplies a [StructureFallback]
 * and an [AuthorityFallback] over its own generated `tenancy` and `access` clients
 * (these are the only places such a client may be used, INV-REQ-1199 behaviour #3);
 * without one a replica has no fallback and serves what it holds. The service's
 * event consumer feeds the replicas through their [ReplicaFeedSink].
 */
@AutoConfiguration
@EnableConfigurationProperties(ReplicaProperties::class)
class ReplicaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun policyReplica(
        clock: Clock,
        properties: ReplicaProperties,
    ): PolicyReplica = PolicyReplica(clock, properties.policyTopics)

    @Bean
    @ConditionalOnMissingBean
    fun structureReplica(
        clock: Clock,
        registry: ResilienceRegistry,
        fallback: ObjectProvider<StructureFallback>,
    ): StructureReplica = StructureReplica(clock, fallback.ifAvailable?.guardedBy(registry.forEdge(TENANCY_EDGE)))

    @Bean
    @ConditionalOnMissingBean
    fun authorityReplica(
        clock: Clock,
        properties: ReplicaProperties,
        registry: ResilienceRegistry,
        fallback: ObjectProvider<AuthorityFallback>,
    ): AuthorityReplica =
        AuthorityReplica(
            clock,
            properties.authorityLagBound,
            fallback.ifAvailable?.guardedBy(registry.forEdge(ACCESS_EDGE)),
        )
}
