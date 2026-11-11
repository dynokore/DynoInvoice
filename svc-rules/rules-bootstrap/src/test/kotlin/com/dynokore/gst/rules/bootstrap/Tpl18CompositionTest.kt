package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.DryRunSampleEvents
import com.dynokore.gst.rules.adapters.events.RulesEventCatalogue
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.Schema
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * TPL-18's composition, built the way the template builds it (P109): the service imports ServiceRuntimeConfiguration
 * then ProductRuntimeConfiguration, `@EnableAutoConfiguration` adds the auto-configurations on the classpath, and the
 * service's own RulesConfiguration supplies its inputs. No database is reached: the pool is lazy.
 */
class Tpl18CompositionTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, RulesConfiguration::class)
    class Tpl18Bootstrap

    /**
     * The test SecretStore mount (STK-29): the installation binding key the transaction runner signs with
     * (INV-T-4156), a test-only value under the secret `tenancy/binding-key-current`.
     */
    private val secrets: Path =
        Files.createTempDirectory("rules-secrets").also { root ->
            Files.createDirectories(root.resolve("tenancy"))
            Files.writeString(root.resolve("tenancy/binding-key-current"), "test-only-binding-key-0123456789ab")
        }

    private fun runner(accessUrl: String? = "http://127.0.0.1:1"): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(Tpl18Bootstrap::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            .withPropertyValues(
                "gst.service=rules-svc",
                *listOfNotNull(accessUrl?.let { "rules.access-svc-url=$it" }).toTypedArray(), // required (TPL-16)
                "gst.instance-id=rules-svc-0",
                "service-runtime.workload-identity.audience=rules-svc",
                "service-runtime.modules.schema=rules",
                "inv.tenancy.reference-tables=rules.pack_registry,rules.pack_entry_source",
                "inv.tenancy.platform-scoped-tables=rules.write_installation_setting,rules.config_purge_tenant",
                "spring.datasource.url=jdbc:postgresql://localhost:1/rules",
                "spring.datasource.username=rules_runtime",
                "service-runtime.pool.profile=minimal",
                "gst.bindings.secret-store.mount-root=$secrets",
                "gst.bindings.telemetry-export=bundled-otel",
                "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
            )

    @Test
    fun `the context starts with the service inputs and wires the use case, the catalogue and the outbox writer`() {
        runner().run { context ->
            context.startupFailure?.let { throw it }
            context.getBean(EvaluateDocument::class.java)
            context.getBean(PackEventEmitter::class.java)
            context.getBean(OutboxWriter::class.java)
            context.getBeansOfType(Schema::class.java).values.map { it.name } shouldContainExactlyInAnyOrder
                listOf("rules")
            context.getBean(EventCatalogue::class.java).entries().map { it.eventType } shouldContainExactlyInAnyOrder
                RulesEventCatalogue.TOPICS.keys.toList() +
                // the platform events of setPolicy (INV-T-18451) and the sample request (INV-T-19903)
                listOf(
                    DryRunSampleEvents.REQUESTED,
                    PlatformEventCatalogue.AUDIT_ENTRY_RECORDED.eventType,
                    PlatformEventCatalogue.POLICY_CHANGED.eventType,
                    PlatformEventCatalogue.EVIDENCE_ARTEFACT_RECORDED.eventType,
                )
        }
    }

    @Test
    fun `start-up fails without rules access-svc-url and with a blank one (TPL-16, no default)`() {
        runner(accessUrl = null).run { context -> (context.startupFailure != null) shouldBe true }
        runner(accessUrl = "").run { context -> (context.startupFailure != null) shouldBe true }
        runner(accessUrl = "   ").run { context -> (context.startupFailure != null) shouldBe true }
        // look-alike that must start: the same context with a value
        runner().run { context -> context.startupFailure shouldBe null }
    }

    @Test
    fun `start-up fails without the two telemetry export properties so they are needed`() {
        ApplicationContextRunner()
            .withUserConfiguration(Tpl18Bootstrap::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            .withPropertyValues(
                "gst.service=rules-svc",
                "rules.access-svc-url=http://127.0.0.1:1", // required (TPL-16); nothing listens there
                "gst.instance-id=rules-svc-0",
                "service-runtime.workload-identity.audience=rules-svc",
                "service-runtime.modules.schema=rules",
                "inv.tenancy.reference-tables=rules.pack_registry,rules.pack_entry_source",
                "inv.tenancy.platform-scoped-tables=rules.write_installation_setting,rules.config_purge_tenant",
                "spring.datasource.url=jdbc:postgresql://localhost:1/rules",
                "spring.datasource.username=rules_runtime",
                "service-runtime.pool.profile=minimal",
                "gst.bindings.secret-store.mount-root=$secrets",
            ).run { context -> (context.startupFailure != null) shouldBe true }
    }

    @Test
    fun `start-up fails when the table classes disagree with the generated schema`() {
        runner().withPropertyValues("inv.tenancy.reference-tables=rules.no_such_table").run { context ->
            (context.startupFailure != null) shouldBe true
        }
    }
}
