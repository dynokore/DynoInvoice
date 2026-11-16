package com.dynokore.gst.evidence.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.UrlResource
import java.net.URL
import java.time.Duration

/**
 * INV-AC-24117: the packaged `application.yaml` (class path resource, no system property, environment variable or
 * profile file) is the configuration baseline of the archive lock period.
 */
class ArchiveLockBaselineTest {
    private fun packaged(without: String? = null): RetentionProperties {
        val loaded = YamlPropertySourceLoader().load("packaged", UrlResource(mainResource()))
        val values = linkedMapOf<String, Any>()
        loaded.forEach { source ->
            (source as EnumerablePropertySource<*>).propertyNames.forEach { name ->
                if (name != without) values[name] = source.getProperty(name)!!
            }
        }
        val env = StandardEnvironment()
        env.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        env.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        env.propertySources.addFirst(MapPropertySource("packaged", values))
        val binder = Binder.get(env)
        return binder.bindOrCreate("evidence.retention", Bindable.of(RetentionProperties::class.java))
    }

    /** The packaged file of the main resources: a file of the same name in the test resources must not stand in. */
    private fun mainResource(): URL =
        javaClass.classLoader
            .getResources("application.yaml")
            .toList()
            .single { !it.path.contains("/test/") }

    @Test
    fun `INV-AC-24117 the packaged baseline sets the archive lock period to 30 days`() {
        packaged().archiveLockPeriod shouldBe Duration.ofDays(30)
    }

    @Test
    fun `INV-AC-24117 without the key the archive lock period is null so a deletion is refused`() {
        packaged(without = KEY).archiveLockPeriod shouldBe null
    }

    private companion object {
        const val KEY = "evidence.retention.archive-lock-period"
    }
}
