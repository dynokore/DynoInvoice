package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.composition.ModuleProperties
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModuleProperties::class)
private class ModulePropertiesConfiguration

/**
 * INV-T-24512: the fail-closed ObjectStore fallback is an auto-configuration ordered after the platform wiring, so the
 * platform S3ObjectStore binds when its settings are present (Spring Boot back-off, OWASP ASVS V1, 12-factor III).
 */
class ObjectStoreFallbackAutoConfigurationTest {
    private val fallbackClass = "com.dynokore.gst.reconciliation.bootstrap.ObjectStoreFallbackAutoConfiguration"
    private val s3ObjectStore = "com.dynokore.platform.evidencestore.S3ObjectStore"
    private val key = ObjectKey("tenant/work-paper/1")

    /** The production wiring: the platform auto-configurations, the fallback, and the service user configuration. */
    private fun runner(mountRoot: Path): ApplicationContextRunner {
        Files.createDirectories(mountRoot.resolve("object-store"))
        Files.writeString(mountRoot.resolve("object-store/access-key-id"), "AKIDEXAMPLE")
        Files.writeString(mountRoot.resolve("object-store/secret-access-key"), "secret-example")
        return ApplicationContextRunner()
            .withUserConfiguration(
                WorkPaperSealConfiguration::class.java,
                // the SealWorkPaper bean reads the work-paper through GetWorkPaper (INV-T-24382)
                WorkPaperReadConfiguration::class.java,
                RunReadConfiguration::class.java,
                ModulePropertiesConfiguration::class.java,
            ).withBean(SecretStore::class.java, { MountedFileSecretStore(mountRoot) })
            .withBean(DSLContext::class.java, { DSL.using(SQLDialect.POSTGRES) })
            .withBean(EvidenceArtefactRecorder::class.java, {
                object : EvidenceArtefactRecorder {
                    override fun record(
                        artefact: EvidenceArtefact,
                        context: EventContext,
                    ) = Unit
                }
            })
            .withPropertyValues("reconciliation.work-paper.retention-days=30")
            .withConfiguration(AutoConfigurations.of(L1CompositionAutoConfiguration::class.java, fallback()))
    }

    private fun fallback(): Class<*> = Class.forName(fallbackClass)

    private val settings =
        arrayOf(
            "service-runtime.object-store.region=ap-southeast-1",
            "service-runtime.modules.evidence.bucket=evidence",
            "service-runtime.object-store.access-key-id-credential=object-store/access-key-id",
            "service-runtime.object-store.secret-access-key-credential=object-store/secret-access-key",
        )

    private fun AssertableApplicationContext.oneStore(): ObjectStore {
        startupFailure shouldBe null
        getBeansOfType(ObjectStore::class.java).keys shouldHaveSize 1
        // WorkPaperSealConfiguration declares no bean of type ObjectStore
        WorkPaperSealConfiguration::class.java.declaredMethods.none {
            ObjectStore::class.java.isAssignableFrom(it.returnType)
        } shouldBe
            true
        return getBean(ObjectStore::class.java)
    }

    @Test
    fun `INV-AC-24511 case 1 the platform S3ObjectStore binds when the object-store settings are present`(
        @TempDir dir: Path,
    ) {
        runner(dir).withPropertyValues(*settings).run { ctx ->
            ctx.oneStore()::class.java.name shouldBe s3ObjectStore
        }
    }

    @Test
    fun `INV-AC-24511 case 2 with no settings the fallback is the only store and refuses put and get`(
        @TempDir dir: Path,
    ) {
        runner(dir).run { ctx ->
            val store = ctx.oneStore()
            store::class.java.name shouldBe UnconfiguredObjectStore::class.java.name
            ctx.getBeanNamesForType(ObjectStore::class.java).toList() shouldBe listOf("unconfiguredObjectStore")
            val message = "no object store is configured for the sealed work-papers"
            shouldThrow<IllegalStateException> { store.put(key, byteArrayOf(1), Instant.EPOCH) }.message shouldBe
                message
            shouldThrow<IllegalStateException> { store.get(key) }.message shouldBe message
        }
    }

    @Test
    fun `INV-AC-24511 case 3 with only the bucket set the fallback is the only store`(
        @TempDir dir: Path,
    ) {
        runner(dir).withPropertyValues("service-runtime.modules.evidence.bucket=evidence").run { ctx ->
            ctx.oneStore().shouldBeInstanceOf<UnconfiguredObjectStore>()
        }
    }

    @Test
    fun `the production application context loads the imports file of the fallback`() {
        val resource = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        val lines =
            javaClass.classLoader
                .getResourceAsStream(resource)!!
                .bufferedReader()
                .readLines()
        lines.filter { it == fallbackClass } shouldHaveSize 1
        ImportCandidates
            .load(
                org.springframework.boot.autoconfigure.AutoConfiguration::class.java,
                javaClass.classLoader,
            ).toList() shouldContain fallbackClass
        // the production application enables auto-configuration, which is what reads that file
        AutoConfigurationImportSelector::class.java.name.isNotEmpty() shouldBe true
        ReconciliationApplication::class.java.isAnnotationPresent(
            org.springframework.boot.autoconfigure.SpringBootApplication::class.java,
        ) shouldBe true
    }
}
