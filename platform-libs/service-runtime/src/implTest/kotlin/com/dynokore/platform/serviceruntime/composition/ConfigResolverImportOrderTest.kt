package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.configresolver.ConfigPurge
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.JdbcPolicyHistory
import com.dynokore.platform.configresolver.JdbcPolicyReader
import com.dynokore.platform.configresolver.JdbcPolicyWriter
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ResolutionRecorder
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.tenancykernel.CurrentConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import java.time.LocalDate
import javax.sql.DataSource

/**
 * INV-T-3881 (ruling INV-Q-3877, INV-Q-3710): config-resolver's ports are wired whatever the order in which a service
 * registers ServiceRuntimeConfiguration and StandInProductRuntimeConfiguration (the [Composition]s of Tpl18Compositions.kt).
 */
class ConfigResolverImportOrderTest {
    /** The inputs every service declares: a DataSource, its name, its schema (README port table). */
    private val service =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBindingKey()
            .withBean(
                "dataSource",
                DataSource::class.java,
                { mockk<DataSource>(relaxed = true) },
                { it.isPrimary = true },
            ).withPropertyValues(
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "service-runtime.modules.schema=submission",
            )

    @ParameterizedTest
    @EnumSource(Composition::class)
    fun `config-resolver's ports are beans and the PolicyReader resolves through product-runtime's catalogue`(
        composition: Composition,
    ) {
        service.withUserConfiguration(composition.application).run { context ->
            context.startupFailure shouldBe null
            listOf(
                PolicyReader::class,
                PolicyHistory::class,
                ConfigPurge::class,
                ResolutionRecorder::class,
                PolicyWriter::class,
            ).forEach { context.getBean(it.java) }
            val catalogue = context.getBean(RecordingCatalogue::class.java)
            val reader = context.getBean(PolicyReader::class.java).shouldBeInstanceOf<JdbcPolicyReader>()
            val key = VariationPointKey("VP-20")
            shouldThrow<UnknownVariationPoint> {
                reader.resolve(
                    key,
                    PolicyScope.installation(),
                    LocalDate.of(2026, 10, 12),
                )
            }
            catalogue.asked shouldContainExactly listOf(key)
            // the optional DelegationProbe product-runtime provides reaches the PolicyWriter
            val writer = context.getBean(PolicyWriter::class.java).shouldBeInstanceOf<JdbcPolicyWriter>()
            writer.delegation shouldBeSameInstanceAs context.getBean(DelegationProbe::class.java)
        }
    }

    @ParameterizedTest
    @EnumSource(Composition::class)
    fun `without product-runtime's optional DelegationProbe the PolicyWriter is still wired, without one`(
        composition: Composition,
    ) {
        service
            .withUserConfiguration(composition.application)
            .withPropertyValues("${StandInProductRuntimeConfiguration.DELEGATION_PROBE}=false")
            .run { context ->
                context.startupFailure shouldBe null
                context.getBeanNamesForType(DelegationProbe::class.java).size shouldBe 0
                context.getBean(PolicyWriter::class.java).shouldBeInstanceOf<JdbcPolicyWriter>().delegation shouldBe
                    null
            }
    }

    @Test
    fun `without a VariationPointCatalogue no config-resolver port is a bean`() {
        listOf(ServiceOnlyApplication::class.java, ServiceOnlyAutoConfiguredApplication::class.java)
            .forEach { application ->
                service.withUserConfiguration(application).run { context ->
                    context.startupFailure shouldBe null
                    context.getBean(CurrentConnection::class.java)
                    listOf(
                        PolicyReader::class,
                        PolicyHistory::class,
                        ConfigPurge::class,
                        ResolutionRecorder::class,
                        PolicyWriter::class,
                    ).forEach { context.getBeanNamesForType(it.java).size shouldBe 0 }
                }
            }
    }

    @Test
    fun `a service's own PolicyReader still replaces the default under TPL-18`() {
        val own = mockk<PolicyReader>()
        service
            .withBean(PolicyReader::class.java, { own })
            .withUserConfiguration(Tpl18Application::class.java)
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(PolicyReader::class.java) shouldBeSameInstanceAs own
                context.getBean(PolicyHistory::class.java).shouldBeInstanceOf<JdbcPolicyHistory>()
            }
    }
}
