package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** INV-REQ-1199: the replicas are wired, with §36.6's 500 ms and one retry on both fallback edges. */
class ReplicaAutoConfigurationTest {
    @TempDir
    lateinit var dir: Path

    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val contextRunner =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBindingKey()
            .withConfiguration(
                AutoConfigurations.of(
                    ServiceRuntimeConfiguration::class.java,
                    L1CompositionAutoConfiguration::class.java,
                ),
            ).withPropertyValues("service-runtime.workload-identity.audience=canonical-svc")

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

    private fun withResource(text: String): ApplicationContextRunner {
        Files.createDirectories(dir.resolve("META-INF/gst"))
        Files.writeString(dir.resolve(DegradedAllowResource.RESOURCE), text)
        return contextRunner.withClassLoader(URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader))
    }

    private fun degradedAsk(
        replica: AuthorityReplica,
        coverage: MachineEntityCoverage,
    ) = replica.decide(
        AuthorizationRequest(
            tenant,
            Principal.machine("client-1", tenant, coverage, "ingest", "jti-1"),
            "E1",
            "ingest",
            operation = OperationKey("POST", "/v1/transactions", "ingest"),
        ),
        Deadline.after(Duration.ofSeconds(30), Clock.systemUTC()),
    )

    private val allowed = AuthorityDecision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
    private val denied = AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)

    @Test
    fun `the authority replica reads the degraded-allow resource and counts an allow in the meter registry`() {
        withResource("${DegradedAllowResource.HEADER}\nAddTx\tPOST\t/v1/transactions\n").run { context ->
            val replica = context.getBean(AuthorityReplica::class.java)
            degradedAsk(replica, MachineEntityCoverage.Entity("E1")) shouldBe allowed
            context
                .getBean(MeterRegistry::class.java)
                .counter(MicrometerDegradedAllowObserver.METER_NAME, "operation", "POST /v1/transactions")
                .count() shouldBe 1.0
        }
    }

    @Test
    fun `without the resource every degraded machine request is denied and nothing is counted`() {
        contextRunner.run { context ->
            degradedAsk(context.getBean(AuthorityReplica::class.java), MachineEntityCoverage.Entity("E1")) shouldBe
                denied
            context
                .getBean(MeterRegistry::class.java)
                .find(MicrometerDegradedAllowObserver.METER_NAME)
                .counter() shouldBe null
        }
    }

    @Test
    fun `tenant coverage is decided by the structure replica of the context`() {
        val entity =
            StructureFallback { _, _ ->
                FallbackResult.Found(StructureNode(StructureNodeKind.ENTITY, null, false, "E1", null, emptyList(), 1))
            }
        val coverage = MachineEntityCoverage.Tenant(tenant.toString())
        withResource("${DegradedAllowResource.HEADER}\nAddTx\tPOST\t/v1/transactions\n").run { context ->
            degradedAsk(context.getBean(AuthorityReplica::class.java), coverage) shouldBe denied
        }
        withResource(
            "${DegradedAllowResource.HEADER}\nAddTx\tPOST\t/v1/transactions\n",
        ).withBean(StructureFallback::class.java, {
            entity
        })
            .run { context ->
                degradedAsk(context.getBean(AuthorityReplica::class.java), coverage) shouldBe allowed
            }
    }

    private companion object {
        const val SLOW_MILLIS = 400L
    }
}
