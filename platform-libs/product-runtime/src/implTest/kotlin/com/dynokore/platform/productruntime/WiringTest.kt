package com.dynokore.platform.productruntime

import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.events.PlatformEventTypes
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.StructureFallback
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import java.time.LocalDate
import javax.sql.DataSource

/** INV-T-2876: ProductRuntimeConfiguration (TPL-18) wires the bindings, and config-resolver over them under TPL-18. */
class WiringTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProductRuntimeConfiguration::class.java))
            .withBean(StructureReplica::class.java, { StructureReplica(CLOCK) })
            .withBean(AuthorityReplica::class.java, { AuthorityReplica(CLOCK) })
            .withBean(PolicyReplica::class.java, { PolicyReplica(CLOCK) })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            .withPropertyValues("gst.service=submission-svc")

    @Test
    fun `wires the catalogue, the delegation probe and the feeds, and the fallbacks once their URLs are set`() {
        runner.run { context ->
            val catalogue = context.getBean(VariationPointCatalogue::class.java)
            catalogue.find(VariationPointKey("VP-01"))?.key shouldBe VariationPointKey("VP-01")
            catalogue.keys().isNotEmpty() shouldBe true
            context.getBean(DelegationProbe::class.java)
            context.getBean(StructureFeedHandler::class.java)
            context.getBean(AuthorityFeedHandler::class.java)
            context.getBean(PolicyFeed::class.java)
            context.getBeansOfType(StructureFallback::class.java).size shouldBe 0
            context.getBeansOfType(AuthorityFallback::class.java).size shouldBe 0
        }
        runner
            .withPropertyValues(
                "product-runtime.fallback.tenancy-svc-url=http://tenancy-svc:8080",
                "product-runtime.fallback.access-svc-url=http://access-svc:8080",
            ).run { context ->
                context.getBean(StructureFallback::class.java)
                context.getBean(AuthorityFallback::class.java)
            }
    }

    @Test
    fun `is an auto-configuration listed for Boot`() {
        ProductRuntimeConfiguration::class.java.isAnnotationPresent(AutoConfiguration::class.java) shouldBe true
        val listing =
            javaClass.classLoader
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .toList()
                .flatMap { it.readText().lines() }
        listing shouldContain ProductRuntimeConfiguration::class.java.name
    }

    /**
     * TPL-18's composition (INV-T-2876 done-when, coordinator ruling INV-Q-3877; INV-Q-3710): the service imports
     * `ServiceRuntimeConfiguration` then `ProductRuntimeConfiguration`, and `@SpringBootApplication`'s
     * `@EnableAutoConfiguration` adds the auto-configurations on its classpath ([Tpl18Bootstrap]; component scanning
     * finds nothing more here). config-resolver's PolicyReader must then exist and resolve through
     * [GstVariationPointCatalogue].
     *
     * DEPENDS ON service-runtime 3.1.1 (INV-T-3881): the order-independent config-resolver wiring. On service-runtime
     * 3.1.0 and earlier this test fails, because ConfigResolverWiring's registration-time
     * `@ConditionalOnBean(VariationPointCatalogue)` is evaluated before product-runtime's catalogue is registered. It is
     * not worked around here: no ordering of ProductRuntimeConfiguration is relied on. (With the imports reversed the
     * same test passes on 3.1.0, which shows the composition and the resolution are otherwise complete.)
     */
    @Test
    fun `under TPL-18's two imports, config-resolver's PolicyReader resolves through the gst-catalogue adapter`() {
        tpl18().run { context ->
            context.startupFailure?.let { throw it }
            context.getBean(VariationPointCatalogue::class.java).shouldBeInstanceOf<GstVariationPointCatalogue>()
            val reader = context.getBean(PolicyReader::class.java)
            val catalogue = GstVariationPointCatalogue("submission-svc")
            // A planned row resolves to its catalogue default without reading a setting (INV-REQ-1251), so the
            // answer can only come from the gst-catalogue rows; a key that is not one of them is refused.
            val planned =
                catalogue.keys().map { catalogue.require(it) }.first {
                    it.status == VpStatus.PLANNED &&
                        it.defaultKeys(DeploymentForm.SAAS) != null &&
                        it.defaultKeys(DeploymentForm.SAAS) == it.defaultKeys(DeploymentForm.PRIVATE)
                }
            val resolved = reader.resolve(planned.key, PolicyScope.tenant(TENANT), LocalDate.parse("2026-10-12"))
            resolved.selected.map { it.key } shouldBe planned.defaultKeys(DeploymentForm.SAAS)
            resolved.source shouldBe PolicySource.CatalogueDefault
            shouldThrow<UnknownVariationPoint> {
                reader.resolve(VariationPointKey("VP-999"), PolicyScope.tenant(TENANT), LocalDate.parse("2026-10-12"))
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class)
    class Tpl18Bootstrap

    /** The bootstrap of TPL-18 with a service's own inputs: its DataSource, meter registry and gst properties. */
    private fun tpl18(): ApplicationContextRunner = ApplicationContextRunner()
        .withUserConfiguration(Tpl18Bootstrap::class.java)
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
        .withBean("dataSource", DataSource::class.java, { mockk<DataSource>(relaxed = true) }, { it.isPrimary = true })
        .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
        .withPropertyValues(
            "gst.service=submission-svc",
            "gst.instance-id=submission-svc-0",
            "service-runtime.workload-identity.audience=submission-svc",
            "service-runtime.modules.schema=submission",
        )

    @Test
    fun `the api catalogue names the same platform types and topics as service-runtime`() {
        PlatformEventCatalogue.POLICY_CHANGED.eventType shouldBe PlatformEventTypes.POLICY_CHANGED
        PlatformEventCatalogue.POLICY_CHANGED.topic shouldBe PlatformEventTypes.POLICY_CHANGED_TOPIC
        PlatformEventCatalogue.AUDIT_ENTRY_RECORDED.eventType shouldBe PlatformEventTypes.AUDIT_ENTRY_RECORDED
        PlatformEventCatalogue.JOB_RUN_RECORDED.eventType shouldBe PlatformEventTypes.JOB_RUN_RECORDED
        PlatformEventCatalogue.JOB_REGISTERED.eventType shouldBe PlatformEventTypes.JOB_REGISTERED
        PlatformEventCatalogue.CONNECTION_STATUS_CHANGED.eventType shouldBe PlatformEventTypes.CONNECTION_STATUS_CHANGED
        PlatformEventCatalogue.CONNECTION_STATUS_CHANGED.topic shouldBe PlatformEventTypes.CONNECTION_STATUS_CHANGED_TOPIC
        PlatformEventCatalogue.EVIDENCE_ARTEFACT_RECORDED.eventType shouldBe PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED
        PlatformEventCatalogue.EVIDENCE_ARTEFACT_RECORDED.topic shouldBe PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED_TOPIC
        PlatformEventCatalogue.PACK_READINESS_ACKNOWLEDGED.eventType shouldBe PlatformEventTypes.PACK_READINESS_ACKNOWLEDGED
    }
}
