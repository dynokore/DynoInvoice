package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** INV-REQ-1199: the replicas are wired, with §36.6's 500 ms and one retry on both fallback edges. */
class ReplicaAutoConfigurationTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val contextRunner =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withConfiguration(AutoConfigurations.of(ServiceRuntimeConfiguration::class.java))
            .withPropertyValues("service-runtime.workload-identity.audience=canonical-svc")

    @Test
    fun `the policy replica waits for every configured policy topic`() {
        contextRunner.withPropertyValues("service-runtime.replica.policy-topics=t.one,t.two").run { context ->
            val replica = context.getBean(PolicyReplica::class.java)
            replica.onSnapshotComplete("t.one", 0)
            replica.lag() shouldBe Lag.Unknown
            replica.onSnapshotComplete("t.two", 0)
            replica.lag() shouldBe Lag.Measured(Duration.ZERO)
        }
    }

    @Test
    fun `wires the three replicas with the one second authority bound and the section 36-6 edge policies`() {
        contextRunner.run { context ->
            context.getBean(PolicyReplica::class.java)
            context.getBean(StructureReplica::class.java)
            context.getBean(AuthorityReplica::class.java).lagBound shouldBe Duration.ofSeconds(1)
            val edges = context.getBean(ResilienceProperties::class.java).edges
            listOf(TENANCY_EDGE, ACCESS_EDGE).forEach { edge ->
                edges.getValue(edge).timeout shouldBe Duration.ofMillis(500)
                edges.getValue(edge).retry?.maxAttempts shouldBe 2
            }
        }
    }

    @Test
    fun `a fallback that outlives its edge timeout counts as down and the last state is served`() {
        val slow =
            StructureFallback { _, _ ->
                Thread.sleep(SLOW_MILLIS)
                FallbackResult.Found(StructureNode(StructureNodeKind.ENTITY, null, false, "e", null, emptyList(), 1))
            }
        contextRunner
            .withBean(StructureFallback::class.java, { slow })
            .withPropertyValues("service-runtime.resilience.edges.tenancy-svc.timeout=50ms")
            .run { context ->
                val structure = context.getBean(StructureReplica::class.java)
                val deadline = Deadline.after(Duration.ofSeconds(30), Clock.systemUTC())
                structure.node(tenant, "never-seen", deadline) shouldBe Lookup.Unavailable
            }
    }

    private companion object {
        const val SLOW_MILLIS = 400L
    }
}
