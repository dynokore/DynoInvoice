package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * The interval of the directory-group synchronisation (owner item INV-Q-8667 answered "15 minutes", read as option A:
 * one required configuration property, `access.idp-group-sync.interval`, an ISO-8601 duration). The owner's figure is
 * set in `application.yaml`; the properties class has no default, and a missing or zero interval refuses the binding.
 */
class IdpGroupSyncIntervalTest {
    private val yaml = File("src/main/resources/application.yaml")

    /** The interval the yaml sets under `access.idp-group-sync.interval`, null when the key is absent. */
    @Suppress("UNCHECKED_CAST")
    private fun intervalIn(text: String): Duration? {
        val root = Yaml().load<Map<String, Any?>>(text)
        val section = (root["access"] as? Map<String, Any?>)?.get("idp-group-sync") as? Map<String, Any?>
        return (section?.get("interval") as? String)?.let { Duration.parse(it) }
    }

    private fun bind(values: Map<String, String>) =
        Binder(
            MapConfigurationPropertySource(values),
        ).bindOrCreate("access.idp-group-sync", IdpGroupSyncProperties::class.java)

    @Test
    fun `the key access idp-group-sync interval in application yaml is 15 minutes`() {
        assertThat(intervalIn(yaml.readText())).isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun `probe - 5 minutes, 60 minutes, 15 seconds, the look-alike key and an absent key all differ from 15 minutes`() {
        val base = yaml.readText()
        listOf("PT5M", "PT60M", "PT15S").forEach { other ->
            assertThat(
                intervalIn(base.replace("interval: PT15M", "interval: $other")),
            ).isNotEqualTo(Duration.ofMinutes(15))
        }
        assertThat(intervalIn(base.replace("  idp-group-sync:", "  idp-group-syncs:"))).isNull()
        assertThat(intervalIn(base.replace("    interval: PT15M", "    intervals: PT15M"))).isNull()
        assertThat(intervalIn(base.replace("PT15M", ""))).isNull()
    }

    @Test
    fun `the properties bind the 15 minutes of the yaml and refuse a missing key or a zero interval`() {
        assertThat(bind(mapOf("access.idp-group-sync.interval" to "PT15M")).interval).isEqualTo(Duration.ofMinutes(15))
        assertThatThrownBy { bind(emptyMap()) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy {
            bind(
                mapOf("access.idp-group-sync.interval" to "PT0S"),
            )
        }.isInstanceOf(Exception::class.java)
        assertThatThrownBy {
            bind(
                mapOf("access.idp-group-sync.interval" to "PT-5M"),
            )
        }.isInstanceOf(Exception::class.java)
        // the look-alike key binds nothing
        assertThatThrownBy {
            bind(
                mapOf("access.idp-group-sync.intervals" to "PT15M"),
            )
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `no source file outside src-test holds a literal default for the interval`() {
        assertThat(DurationDefaultScan.offenders(File("..").absoluteFile.normalize())).isEmpty()
    }

    @Test
    fun `the scan flags a Kotlin default and a duration literal in a file about the property and ignores tests`(
        @TempDir root: Path,
    ) {
        fun write(
            path: String,
            text: String,
        ) = root
            .resolve(path)
            .also { it.parent.toFile().mkdirs() }
            .toFile()
            .writeText(text)
        write(
            "m/src/main/kotlin/A.kt",
            "// access.idp-group-sync\ndata class P(val interval: Duration = Duration.ofMinutes(15))",
        )
        write("m/src/main/kotlin/B.kt", "val x = \"access.idp-group-sync\"\nval d = Duration.parse(\"PT15M\")")
        write("m/src/main/kotlin/C.kt", "class IdpGroupSyncProperties(val interval:\n    Duration = DEFAULT)")
        write("m/src/main/kotlin/D.kt", "class Other(val interval: Duration = Duration.ofMinutes(1))")
        write("m/src/test/kotlin/E.kt", "class T { val interval: Duration = Duration.ofMinutes(15) } // idp-group-sync")
        val offenders = DurationDefaultScan.offenders(root.toFile()).map { it.name }.sorted()
        assertThat(offenders).containsExactly("A.kt", "B.kt", "C.kt")
    }
}

/** The `.kt` files about the sync interval that hold a duration literal or a default value for it outside tests. */
object DurationDefaultScan {
    private val about = Regex("""idp-group-sync|IdpGroupSyncProperties|idpGroupSync""")
    private val literal = Regex("""Duration\.(?:of\w+|parse)\s*\(|:\s*Duration\s*=|Duration\s*=\s*\w""")

    fun offenders(root: File): List<File> =
        root
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && !it.invariantSeparatorsPath.contains("/src/test/") }
            .filter { file -> file.readText().let { about.containsMatchIn(it) && literal.containsMatchIn(it) } }
            .toList()
}
