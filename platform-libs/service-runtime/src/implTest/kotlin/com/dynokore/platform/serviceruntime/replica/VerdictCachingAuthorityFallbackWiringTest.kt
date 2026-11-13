package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** P109, INV-AC-9180: the wired authority replica reuses a Found answer and the ttl is validated at start. */
class VerdictCachingAuthorityFallbackWiringTest {
    private val property = "service-runtime.replica.authority-fallback-cache-ttl"
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val calls = AtomicInteger()
    private val stub =
        AuthorityFallback { _, _ ->
            calls.incrementAndGet()
            FallbackResult.Found(AuthorityVerdict(true))
        }
    private val runner =
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

    private fun startupMessage(context: AssertableApplicationContext): String =
        generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `INV-AC-9180 the wired replica reaches the stub once for two equal questions`() {
        runner.run { context ->
            val replica = context.getBean(AuthorityReplica::class.java)
            val request = AuthorizationRequest(tenant, Principal("p1", PrincipalKind.HUMAN), "E1", "X")
            val deadline = Deadline.after(Duration.ofSeconds(30), Clock.systemUTC())
            replica.decide(request, deadline).source shouldBe DecisionSource.FALLBACK
            replica.decide(request, deadline).source shouldBe DecisionSource.FALLBACK
            calls.get() shouldBe 1
            context
                .getBean(MeterRegistry::class.java)
                .counter(VerdictCachingAuthorityFallback.CACHE_HIT_METER)
                .count() shouldBe 1.0
        }
    }

    @Test
    fun `INV-AC-9180 a ttl of 1001 ms refuses to start and names the key`() {
        runner.withPropertyValues("$property=1001ms").run { context ->
            val message = startupMessage(context)
            (context.startupFailure != null) shouldBe true
            message shouldContain property
            message shouldNotContain "p1"
        }
    }

    @Test
    fun `INV-AC-9180 a ttl of zero is refused`() {
        runner.withPropertyValues("$property=0ms").run { context ->
            startupMessage(context) shouldContain property
        }
    }

    @Test
    fun `INV-AC-9180 a ttl of 1000 ms starts`() {
        runner.withPropertyValues("$property=1000ms").run { context ->
            context.startupFailure shouldBe null
        }
    }
}
