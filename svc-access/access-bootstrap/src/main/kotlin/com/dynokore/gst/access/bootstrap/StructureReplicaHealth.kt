package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.authorise.AuthoriseGate
import com.dynokore.gst.access.application.authorise.StructureReadiness
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The `structureReplica` component of the `ready` group (INV-AC-9089, INV-REQ-1228 behavior 1): DOWN while the
 * structure replica has no snapshot, UP once it has. It is not in the `live` group, so liveness never fails for it.
 */
class StructureReplicaHealthIndicator(
    private val structure: StructureReplica,
) : HealthIndicator {
    override fun health(): Health =
        if (structure.isSynced()) Health.up().build() else Health.down().withDetail("snapshot", "not held").build()
}

@Configuration(proxyBeanMethods = false)
class StructureReplicaHealthConfiguration {
    /** Bean name `structureReplica` is the health component name. */
    @Bean
    fun structureReadiness(structure: StructureReplica): StructureReadiness = StructureReadiness(structure::isSynced)

    @Bean
    fun authoriseGate(
        readiness: StructureReadiness,
        authorise: Authorise,
    ) = AuthoriseGate(readiness, authorise)

    @Bean
    fun structureReplicaHealthIndicator(structure: StructureReplica): HealthIndicator =
        StructureReplicaHealthIndicator(structure)
}
