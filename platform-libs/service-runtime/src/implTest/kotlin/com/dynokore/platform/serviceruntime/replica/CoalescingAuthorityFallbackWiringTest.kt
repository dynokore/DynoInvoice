package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.serviceruntime.resilience.Deadline
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** P109: the context built by [ReplicaAutoConfiguration] wraps the service's access fallback in the coalescing decorator. */
class CoalescingAuthorityFallbackWiringTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val calls = AtomicInteger()
    private val release = CountDownLatch(1)
    private val stub =
        AuthorityFallback { _, _ ->
            calls.incrementAndGet()
            release.await(10, TimeUnit.SECONDS)
            FallbackResult.Found(AuthorityVerdict(true))
        }

    @Test
    fun `identical concurrent decisions on the wired authority replica make one access call`() {
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(AuthorityFallback::class.java, { stub })
            .withBindingKey()
            .withConfiguration(
                AutoConfigurations.of(
                    ServiceRuntimeConfiguration::class.java,
                    L1CompositionAutoConfiguration::class.java,
                ),
            ).withPropertyValues("service-runtime.workload-identity.audience=canonical-svc")
            .run { context ->
                val replica = context.getBean(AuthorityReplica::class.java)
                val meters = context.getBean(MeterRegistry::class.java)
                val pool = Executors.newFixedThreadPool(5)
                try {
                    val request = AuthorizationRequest(tenant, Principal("p1", PrincipalKind.HUMAN), "E1", "X")
                    val deadline = Deadline.after(Duration.ofSeconds(30), Clock.systemUTC())
                    val futures = (1..5).map { pool.submit<AuthorityDecision> { replica.decide(request, deadline) } }
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (meters.counter(CoalescingAuthorityFallback.COALESCED_METER).count() < 4.0 &&
                        System.nanoTime() < end
                    ) {
                        Thread.sleep(5)
                    }
                    release.countDown()
                    futures.forEach { it.get(10, TimeUnit.SECONDS).source shouldBe DecisionSource.FALLBACK }
                    calls.get() shouldBe 1
                    meters.counter(CoalescingAuthorityFallback.COALESCED_METER).count() shouldBe 4.0
                } finally {
                    release.countDown()
                    pool.shutdownNow()
                }
            }
    }
}
