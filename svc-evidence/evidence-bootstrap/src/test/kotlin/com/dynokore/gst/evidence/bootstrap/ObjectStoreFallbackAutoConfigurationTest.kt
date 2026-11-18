package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.S3ObjectStore
import com.dynokore.platform.serviceruntime.binding.Secret
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.composition.ModuleProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.time.Instant

/**
 * INV-T-24510: the fail-closed ObjectStore fallback is an auto-configuration ordered after the platform wiring, so
 * the platform S3ObjectStore binds when the object-store settings are present (P109: the production wiring).
 */
class ObjectStoreFallbackAutoConfigurationTest {
    private class FakeSecrets : SecretStore {
        override fun read(ref: SecretRef): Secret =
            when (ref.value) {
                "object-store/access-key-id", "object-store/secret-access-key" -> Secret("fake-${ref.value}")
                else -> throw SecretNotFound(ref)
            }
    }

    /** What ServiceRuntimeConfiguration contributes to this wiring: the binding of `service-runtime.modules.*`. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModuleProperties::class)
    class ModulePropertiesOnly

    private val full =
        arrayOf(
            "service-runtime.object-store.region=us-east-1",
            "service-runtime.modules.evidence.bucket=evidence-archive",
            "service-runtime.object-store.access-key-id-credential=object-store/access-key-id",
            "service-runtime.object-store.secret-access-key-credential=object-store/secret-access-key",
        )

    /** The imports file's classes, in the order it gives, plus the service user configuration. */
    private fun runner(vararg properties: String): ApplicationContextRunner {
        val names = importedClassNames()
        val imported = listOf(L1CompositionAutoConfiguration::class.java.name) + names
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(*imported.map { Class.forName(it) }.toTypedArray()))
            .withUserConfiguration(ReturnConsumerConfiguration::class.java, ModulePropertiesOnly::class.java)
            .withBean(SecretStore::class.java, { FakeSecrets() })
            // the consumer needs the database ports, which this test does not build: it is never started
            .withInitializer { context ->
                context.addBeanFactoryPostProcessor { factory ->
                    factory.getBeanDefinition("gstReturnRecordedConsumer").isLazyInit = true
                }
            }.withPropertyValues(*properties)
    }

    private fun importedClassNames(): List<String> =
        checkNotNull(javaClass.classLoader.getResource(IMPORTS))
            .readText()
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun `INV-AC-24509 case 1 the platform S3ObjectStore binds when the object-store settings are present`() {
        runner(*full).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(ObjectStore::class.java).size shouldBe 1
            (context.getBean(ObjectStore::class.java) is S3ObjectStore) shouldBe true
            returnConsumerObjectStoreBeans() shouldBe 0
        }
    }

    @Test
    fun `INV-AC-24509 case 2 with no settings the fallback is the only store and refuses put and get`() {
        runner().run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(ObjectStore::class.java).size shouldBe 1
            val store = context.getBean(ObjectStore::class.java)
            store::class.java.enclosingClass shouldBe ObjectStoreFallbackAutoConfiguration::class.java
            val key = ObjectKey("period-digests/t/e/p/1.json")
            shouldThrow<IllegalStateException> { store.put(key, byteArrayOf(1), Instant.EPOCH) }.message shouldBe
                "no object store is configured"
            shouldThrow<IllegalStateException> { store.get(key) }.message shouldBe "no object store is configured"
            returnConsumerObjectStoreBeans() shouldBe 0
        }
    }

    @Test
    fun `INV-AC-24509 case 3 with only the bucket set the fallback is the only store`() {
        runner("service-runtime.modules.evidence.bucket=evidence-archive").run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(ObjectStore::class.java).size shouldBe 1
            (context.getBean(ObjectStore::class.java) is S3ObjectStore) shouldBe false
            context.getBean(ObjectStore::class.java)::class.java.enclosingClass shouldBe
                ObjectStoreFallbackAutoConfiguration::class.java
            returnConsumerObjectStoreBeans() shouldBe 0
        }
    }

    @Test
    fun `the production application context loads the imports file and it names the fallback class alone`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBean(ObjectStore::class.java)::class.java.enclosingClass shouldBe
                ObjectStoreFallbackAutoConfiguration::class.java
        }
        val text =
            checkNotNull(javaClass.classLoader.getResource(IMPORTS)).readText()
        text shouldBe "${ObjectStoreFallbackAutoConfiguration::class.java.name}\n"
        text shouldBe "com.dynokore.gst.evidence.bootstrap.ObjectStoreFallbackAutoConfiguration\n"
    }

    private fun returnConsumerObjectStoreBeans(): Int =
        ReturnConsumerConfiguration::class.java.declaredMethods
            .filter { it.isAnnotationPresent(org.springframework.context.annotation.Bean::class.java) }
            .count { ObjectStore::class.java.isAssignableFrom(it.returnType) }

    private companion object {
        const val IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    }
}
