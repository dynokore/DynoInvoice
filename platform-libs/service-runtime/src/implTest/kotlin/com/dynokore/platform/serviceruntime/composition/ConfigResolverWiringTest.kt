package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.configresolver.ConfigPurge
import com.dynokore.platform.configresolver.JdbcConfigPurge
import com.dynokore.platform.configresolver.JdbcResolutionRecorder
import com.dynokore.platform.configresolver.ResolutionRecorder
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock

/**
 * INV-T-5303 (P109): [ConfigResolverWiring] builds the purge and the recorder over the module's DSLContext only when
 * the context has a [TransactionRunner] (coordinator ruling INV-Q-5275), and a service's own port replaces them.
 */
class ConfigResolverWiringTest {
    private val base =
        ApplicationContextRunner()
            .withUserConfiguration(ConfigResolverWiring::class.java)
            .withBean(CurrentConnection::class.java, { mockk<CurrentConnection>() })
            .withBean(VariationPointCatalogue::class.java, { mockk<VariationPointCatalogue>() })
            .withBean(GstProperties::class.java, { GstProperties() })
            .withBean(ModuleProperties::class.java, { ModuleProperties(schema = "submission") })
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withPropertyValues("service-runtime.modules.schema=submission")

    private val withRunner =
        base
            .withBean(
                "transactionRunner",
                TransactionRunner::class.java,
                { mockk<TransactionRunner>(moreInterfaces = arrayOf(CurrentConnection::class)) },
                { it.isPrimary = true },
            )
            .withBean(TenantContext::class.java, { mockk<TenantContext>() })

    @Test
    fun `with a TransactionRunner the purge and the recorder are beans`() {
        withRunner.run { context ->
            context.startupFailure shouldBe null
            context.getBean("configPurge").shouldBeInstanceOf<JdbcConfigPurge>()
            context.getBean("resolutionRecorder").shouldBeInstanceOf<JdbcResolutionRecorder>()
            context.getBeansOfType(ConfigPurge::class.java).size shouldBe 1
            context.getBeansOfType(ResolutionRecorder::class.java).size shouldBe 1
        }
    }

    @Test
    fun `without a TransactionRunner neither the purge nor the recorder is a bean`() {
        base.run { context ->
            context.startupFailure shouldBe null
            context.containsBean("configPurge") shouldBe false
            context.containsBean("resolutionRecorder") shouldBe false
            context.getBeanNamesForType(ConfigPurge::class.java).size shouldBe 0
            context.getBeanNamesForType(ResolutionRecorder::class.java).size shouldBe 0
        }
    }

    @Test
    fun `a service's own purge and recorder replace the defaults`() {
        val purge = mockk<ConfigPurge>()
        val recorder = mockk<ResolutionRecorder>()
        withRunner
            .withBean(ConfigPurge::class.java, { purge })
            .withBean(ResolutionRecorder::class.java, { recorder })
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(ConfigPurge::class.java) shouldBeSameInstanceAs purge
                context.getBean(ResolutionRecorder::class.java) shouldBeSameInstanceAs recorder
            }
    }
}
