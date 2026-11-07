package com.dynokore.gst.rules.bootstrap

import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import java.io.File
import java.nio.file.Files

/**
 * INV-T-9237 (INV-Q-8972 A, INV-Q-10107 C): the setting decision of VP-39 is the shared setPolicy's, by the permission
 * `vp-39.set` through the one AuthorityReplica; this service binds no role resolver and names no role.
 */
class PolicySettingDecisionWiringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class, RulesConfiguration::class)
    class Boot

    private val secrets =
        Files.createTempDirectory("rules-policy-secrets").also {
            Files.createDirectories(it.resolve("tenancy"))
            Files.writeString(it.resolve("tenancy/binding-key-current"), "test-only-binding-key-0123456789ab")
        }

    private fun runner() =
        ApplicationContextRunner()
            .withUserConfiguration(Boot::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { RestClient.builder() })
            .withPropertyValues(
                "gst.service=rules-svc",
                "gst.instance-id=rules-svc-0",
                "service-runtime.workload-identity.audience=rules-svc",
                "service-runtime.modules.schema=rules",
                "inv.tenancy.reference-tables=rules.pack_registry,rules.pack_entry_source,rules.pack_artefact",
                "spring.datasource.url=jdbc:postgresql://localhost:1/rules",
                "spring.datasource.username=rules_runtime",
                "service-runtime.pool.profile=minimal",
                "gst.bindings.secret-store.mount-root=$secrets",
                "gst.bindings.telemetry-export=bundled-otel",
                "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
            )

    @Test
    fun `the production context holds exactly one AuthorityReplica and a PolicyWriter and no role resolver bean`() {
        runner().run { context ->
            context.startupFailure?.let { throw it }
            context.getBeansOfType(AuthorityReplica::class.java).keys shouldBe setOf("authorityReplica")
            context.getBeansOfType(PolicyWriter::class.java).keys shouldBe setOf("policyWriter")
            context.beanDefinitionNames
                .map { context.getType(it)?.name.orEmpty() }
                .filter { it.endsWith("PolicyRoleResolver") }
                .shouldBeEmpty()
        }
    }

    @Test
    fun `the production code names no PolicyRoleResolver, no rolesOf and no tax approver role literal`() {
        val main = File(File("..").canonicalFile, "rules-bootstrap/src/main")
        val hits =
            main
                .walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "yaml") }
                .flatMap { f ->
                    f.readLines().filter { "PolicyRoleResolver" in it || "rolesOf" in it || "\"tax.approver\"" in it }
                }.toList()
        hits.shouldBeEmpty()
    }
}
