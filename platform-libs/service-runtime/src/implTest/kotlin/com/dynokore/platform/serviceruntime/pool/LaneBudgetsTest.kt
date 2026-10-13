package com.dynokore.platform.serviceruntime.pool

import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.datasource.Tpl18JdbcApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The lane defaults per installation profile (INV-REQ-1230 b1, INV-ADR-3968 item 1; owner ruling INV-Q-4023), as the
 * INV-T-3977 capacity model publishes them, and their configuration.
 */
class LaneBudgetsTest {
    @ParameterizedTest(name = "{0} runs-reprocessing={1}: {2} / {3} / {4}")
    @CsvSource(
        "MINIMAL, true, 4, 2, 1",
        "MINIMAL, false, 4, 2, 0",
        "HA, true, 7, 3, 1",
        "HA, false, 7, 3, 0",
        "SAAS, true, 7, 3, 1",
        "SAAS, false, 7, 3, 0",
    )
    fun `the defaults match the capacity model`(
        profile: InstallationProfile,
        runsReprocessing: Boolean,
        request: Int,
        background: Int,
        reprocessing: Int,
    ) {
        val budgets = LaneBudgets.defaults(profile, runsReprocessing)
        budgets shouldBe LaneBudgets(request, background, reprocessing)
        budgets.maximum shouldBe request + background + reprocessing
        PoolLaneProperties(profile, runsReprocessing).budgets() shouldBe budgets
    }

    @Test
    fun `the event-log pool is 0 3 0`() {
        LaneBudgets.EVENT_LOG shouldBe LaneBudgets(0, 3, 0)
        LaneBudgets.EVENT_LOG.maximum shouldBe ServiceDataSources.EVENT_LOG_MAXIMUM
    }

    @Test
    fun `a declaring service without reprocessing permits, and a non-declaring one with them, are refused`() {
        shouldThrow<IllegalStateException> {
            PoolLaneProperties(InstallationProfile.MINIMAL, true, LaneOverrides(reprocessing = 0)).budgets()
        }.message shouldContain "runs-reprocessing is true, but the reprocessing budget is 0"
        shouldThrow<IllegalStateException> {
            PoolLaneProperties(InstallationProfile.HA, false, LaneOverrides(reprocessing = 1)).budgets()
        }.message shouldContain "the reprocessing budget is 1, but service-runtime.pool.runs-reprocessing is false"
    }

    @Test
    fun `a lane override replaces the profile default, and the pool maximum is the sum`() {
        PoolLaneProperties(InstallationProfile.HA, lanes = LaneOverrides(background = 5)).budgets() shouldBe LaneBudgets(7, 5, 0)
        PoolLaneProperties(null, lanes = LaneOverrides(2, 1, 0)).budgets() shouldBe LaneBudgets(2, 1, 0)
    }

    @Test
    fun `start-up fails without a profile or lanes, and on a pool maximum other than the sum`() {
        val service = ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withUserConfiguration(Tpl18JdbcApplication::class.java)
            .withPropertyValues(
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "gst.instance-id=submission-svc-0",
                "spring.flyway.enabled=false",
                "spring.datasource.url=jdbc:postgresql://localhost:5432/submission",
                "spring.datasource.username=submission_runtime",
            )
        // INV-Q-4496: no default profile
        service.run {
            it.startupFailure!!.rootMessage() shouldContain
                "set service-runtime.pool.profile (minimal, ha or saas) or service-runtime.pool.lanes.request, " +
                "service-runtime.pool.lanes.background, service-runtime.pool.lanes.reprocessing"
        }
        service.withPropertyValues("service-runtime.pool.lanes.request=2", "service-runtime.pool.lanes.background=1", "service-runtime.pool.lanes.reprocessing=0").run {
            it.startupFailure shouldBe null
            (it.getBean("dataSource") as LanedDataSource).budgets shouldBe LaneBudgets(2, 1, 0)
        }
        service.withPropertyValues("service-runtime.pool.lanes.request=2", "service-runtime.pool.lanes.reprocessing=0").run {
            val message = it.startupFailure!!.rootMessage()
            message shouldContain "or service-runtime.pool.lanes.background"
            message shouldNotContain "lanes.request"
        }
        service.withPropertyValues("service-runtime.pool.profile=large").run { context ->
            generateSequence(context.startupFailure!!) { it.cause }.any { "service-runtime.pool.profile" in it.message.orEmpty() } shouldBe true
            context.startupFailure!!.rootMessage() shouldContain "large"
        }
        service.withPropertyValues("service-runtime.pool.profile=minimal", "spring.datasource.hikari.maximum-pool-size=10").run {
            it.startupFailure!!.rootMessage() shouldContain "maximum-pool-size is 10, but the application pool's maximum is the sum"
        }
        service.withPropertyValues("service-runtime.pool.profile=saas", "service-runtime.pool.runs-reprocessing=true").run { context ->
            context.startupFailure shouldBe null
            (context.getBean("dataSource") as LanedDataSource).budgets shouldBe LaneBudgets(7, 3, 1)
        }
        // INV-Q-4485: the declaration and the reprocessing budget disagree, at start-up
        service.withPropertyValues("service-runtime.pool.profile=saas", "service-runtime.pool.runs-reprocessing=true", "service-runtime.pool.lanes.reprocessing=0").run {
            it.startupFailure!!.rootMessage() shouldContain "runs-reprocessing is true, but the reprocessing budget is 0"
        }
        service.withPropertyValues("service-runtime.pool.profile=minimal", "service-runtime.pool.lanes.reprocessing=1").run {
            it.startupFailure!!.rootMessage() shouldContain "but service-runtime.pool.runs-reprocessing is false"
        }
    }

    private fun Throwable.rootMessage(): String = generateSequence(this) { it.cause }.last().message.orEmpty()
}
