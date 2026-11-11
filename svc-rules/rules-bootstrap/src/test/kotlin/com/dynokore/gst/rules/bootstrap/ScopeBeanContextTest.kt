package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.domain.scope.ScopeFacts
import com.dynokore.gst.rules.domain.scope.ScopeVerdict
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path

/** The one production [ScopeVerdictPort] bean (INV-Q-13820), built the way TPL-18 builds the context (P109). */
class ScopeBeanContextTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, RulesConfiguration::class)
    class Bootstrap

    private val secrets: Path =
        Files.createTempDirectory("rules-scope-secrets").also { root ->
            Files.createDirectories(root.resolve("tenancy"))
            Files.writeString(root.resolve("tenancy/binding-key-current"), "test-only-binding-key-0123456789ab")
        }

    private fun runner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(Bootstrap::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            .withPropertyValues(
                "gst.service=rules-svc",
                "rules.access-svc-url=http://127.0.0.1:1", // required (TPL-16); nothing listens there
                "rules.dry-run.parallelism=1", // required by the dry-run capacity (INV-T-4974)
                "rules.dry-run.sample-wait=PT1S",
                "rules.dry-run.retry-after=PT2S",
                "gst.instance-id=rules-svc-0",
                "service-runtime.workload-identity.audience=rules-svc",
                "service-runtime.modules.schema=rules",
                "inv.tenancy.reference-tables=rules.pack_registry,rules.pack_entry_source,rules.pack_artefact",
                "inv.tenancy.platform-scoped-tables=rules.write_installation_setting,rules.config_purge_tenant",
                "spring.datasource.url=jdbc:postgresql://localhost:1/rules",
                "spring.datasource.username=rules_runtime",
                "service-runtime.pool.profile=minimal",
                "gst.bindings.secret-store.mount-root=$secrets",
                "gst.bindings.telemetry-export=bundled-otel",
                "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
            )

    private val second =
        object : ScopeVerdictPort {
            override fun verdictOf(facts: ScopeFacts) = ScopeVerdict.SUBMITTABLE
        }

    @Test
    fun `the context holds exactly one ScopeVerdictPort bean and it is the pack-backed implementation`() {
        runner().run { context ->
            context.startupFailure?.let { throw it }
            context.getBeansOfType(ScopeVerdictPort::class.java).values.map { it.javaClass } shouldBe
                listOf(PackScopeVerdictPort::class.java)
        }
    }

    @Test
    fun `a second ScopeVerdictPort bean makes the context fail to start`() {
        runner().withBean("secondScopeVerdictPort", ScopeVerdictPort::class.java, { second }).run { context ->
            (context.startupFailure != null) shouldBe true
        }
    }

    @Test
    fun `the context holds the entity-profile consumer that feeds the structure replica`() {
        runner().run { context ->
            context.startupFailure?.let { throw it }
            context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldBe
                setOf("entityProfileConsumer", "dryRunSampleConsumer")
        }
    }
}
