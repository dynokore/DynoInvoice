package com.dynokore.gst.rules.bootstrap

import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import java.nio.file.Files

/**
 * INV-T-18451 (INV-Q-8972 A): the production configuration (`application.yaml` of this module, loaded the way Boot
 * loads it, TPL-18) mounts the shared setPolicy once. The property `service-runtime.idempotency.schema` names the
 * service schema `rules`, and the context holds one `ServletRegistrationBean` whose servlet is the
 * `PolicyCommandServlet` at the policies route (PolicyCommandServlet.MAPPING), over the one `PolicyWriter` that
 * service-runtime supplies.
 */
class PolicyCommandMountTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, RulesConfiguration::class)
    class Boot

    private val secrets =
        Files.createTempDirectory("rules-mount-secrets").also {
            Files.createDirectories(it.resolve("tenancy"))
            Files.writeString(it.resolve("tenancy/binding-key-current"), "test-only-binding-key-0123456789ab")
        }

    /** The production yaml first, then only what a test process must add (no database, no secret mount). */
    private fun runner(vararg extra: String) =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Boot::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            // the token resolver is the resource-server configuration in production; the mount waits for it
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            .withPropertyValues(
                "rules.access-svc-url=http://127.0.0.1:1", // required (TPL-16); nothing listens there
                "rules.dry-run.parallelism=1", // required by the dry-run capacity (INV-T-4974)
                "rules.dry-run.sample-wait=PT1S",
                "gst.instance-id=rules-svc-0",
                "service-runtime.workload-identity.audience=rules-svc",
                "spring.datasource.url=jdbc:postgresql://localhost:1/rules",
                "service-runtime.pool.profile=minimal",
                "gst.bindings.secret-store.mount-root=$secrets",
                "gst.bindings.telemetry-export=bundled-otel",
                "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
                *extra,
            )

    private fun registrations(context: AssertableApplicationContext) =
        context.getBeansOfType(ServletRegistrationBean::class.java).values.toList()

    @Test
    fun `the production configuration mounts exactly one PolicyCommandServlet over one PolicyWriter`() {
        runner().run { context ->
            context.startupFailure?.let { throw it }
            context.environment.getProperty("service-runtime.idempotency.schema") shouldBe "rules"
            context.getBeansOfType(PolicyWriter::class.java).size shouldBe 1
            val all = registrations(context)
            all.size shouldBe 1
            all.single().servlet.shouldBeInstanceOf<PolicyCommandServlet>()
            all.single().urlMappings shouldBe setOf("/internal/v1/policies/*")
            PolicyCommandServlet.MAPPING shouldBe "/internal/v1/policies/*"
        }
    }
}
