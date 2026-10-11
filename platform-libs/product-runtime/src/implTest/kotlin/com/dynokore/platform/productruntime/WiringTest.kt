package com.dynokore.platform.productruntime

import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.events.PlatformEventTypes
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.StructureFallback
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient

/** INV-T-2876: ProductRuntimeConfiguration (TPL-18) wires the bindings and orders itself before service-runtime's. */
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
    fun `is an auto-configuration listed for Boot and ordered before service-runtime's composition root`() {
        val annotation = ProductRuntimeConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)
        annotation.beforeName.toList() shouldContain "com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration"
        val listing =
            javaClass.classLoader
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .toList()
                .flatMap { it.readText().lines() }
        listing shouldContain ProductRuntimeConfiguration::class.java.name
    }

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
