package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.connection.RotationSettings
import com.dynokore.gst.masterdata.domain.connection.HealthSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** INV-REQ-836, INV-REQ-837: the health and rotation settings come from configuration; the code holds no number. */
class ConnectionHealthSettingsTest {
    private val window = "masterdata.connections.health.window"
    private val percentile = "masterdata.connections.health.percentile"
    private val threshold = "masterdata.connections.health.failure-threshold"
    private val openFor = "masterdata.connections.health.open-for"
    private val unresolved = "\${masterdata.absent.value}"

    @Test
    fun `the four shipped values equal 300 seconds, 95, 5 consecutive failures and 30 seconds`() {
        BoundaryFixture.runner().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(HealthSettings::class.java))
                .isEqualTo(HealthSettings(Duration.ofSeconds(300), 5, Duration.ofSeconds(30), 95))
        }
    }

    @Test
    fun `the four properties map to the fields of HealthSettings by equality`() {
        BoundaryFixture
            .runner()
            .withPropertyValues("$window=PT120S", "$percentile=90", "$threshold=7", "$openFor=PT10S")
            .run { context ->
                assertThat(context).hasNotFailed()
                val settings = context.getBean(HealthSettings::class.java)
                assertThat(settings.window).isEqualTo(Duration.ofSeconds(120))
                assertThat(settings.percentile).isEqualTo(90)
                assertThat(settings.failureThreshold).isEqualTo(7)
                assertThat(settings.openFor).isEqualTo(Duration.ofSeconds(10))
            }
    }

    @Test
    fun `an unset property fails the start, each of the four`() {
        listOf(window, percentile, threshold, openFor).forEach { key ->
            BoundaryFixture.runner().withPropertyValues("$key=$unresolved").run { assertThat(it).hasFailed() }
        }
    }

    @Test
    fun `a percentile of 0 or 101, a threshold of 0 and a zero window fail the start`() {
        listOf("$percentile=0", "$percentile=101", "$threshold=0", "$window=PT0S", "$openFor=PT0S").forEach {
            BoundaryFixture.runner().withPropertyValues(it).run { context -> assertThat(context).hasFailed() }
        }
    }

    @Test
    fun `the overlap is unset in the shipped configuration and read from masterdata connections overlap when set`() {
        BoundaryFixture.runner().run { context ->
            assertThat(context.getBean(RotationSettings::class.java).overlap).isNull()
        }
        BoundaryFixture.runner().withPropertyValues("masterdata.connections.overlap=PT2H").run { context ->
            assertThat(context.getBean(RotationSettings::class.java).overlap).isEqualTo(Duration.ofHours(2))
        }
        BoundaryFixture.runner().withPropertyValues("masterdata.connections.overlap=   ").run { context ->
            assertThat(context.getBean(RotationSettings::class.java).overlap).isNull()
        }
    }

    private val skipped = setOf("build", ".git", ".gradle", ".gradle-cache")

    private fun mainKotlin(): List<Path> =
        listOf("masterdata-application", "masterdata-adapters", "masterdata-bootstrap")
            .map {
                Path
                    .of(System.getProperty("user.dir"))
                    .parent
                    .resolve(it)
                    .resolve("src/main/kotlin")
            }.filter { Files.isDirectory(it) }
            .flatMap { root ->
                Files.walk(root).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .filter { path -> path.none { it.toString() in skipped } }
                        .toList()
                }
            }

    /** The code lines of the files (a comment or documentation line is not code). */
    private fun codeLines(): List<Triple<Path, Int, String>> =
        mainKotlin().flatMap { file ->
            Files.readAllLines(file).mapIndexedNotNull { index, line ->
                val text = line.trim()
                if (text.startsWith("*") || text.startsWith("//") ||
                    text.startsWith("/*")
                ) {
                    null
                } else {
                    Triple(file, index + 1, line)
                }
            }
        }

    @Test
    fun `the literals 300 and 95 are in no main Kotlin code line`() {
        assertThat(mainKotlin()).isNotEmpty()
        val found = codeLines().filter { Regex("""\b(300|95)\b""").containsMatchIn(it.third) }
        assertThat(found).isEmpty()
    }

    @Test
    fun `the day counts 14 and 3 are written once, in the constant DEFAULT_OFFSET_DAYS`() {
        val found =
            codeLines().filter {
                !it.third.contains('"') &&
                    Regex("""\b(14|3)\b""").containsMatchIn(it.third) &&
                    Regex("""(?i)days|offset|ofDays""").containsMatchIn(it.third)
            }
        assertThat(found.map { it.third.trim() }).containsExactly("val DEFAULT_OFFSET_DAYS: List<Int> = listOf(14, 3)")
    }
}
