package com.dynokore.gst.transport.acceptance

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Duration

/**
 * INV-AC-1767: the runbook states, for each AP connection, the expected acknowledgement latency and the AP's
 * failure-management protocol, and those values equal the configuration (VP-48). The values are read from the
 * runbook and from `application.yaml`'s defaults in `ApProperties`, by equality.
 */
class RunbookTest {
    private val root = File(System.getProperty("gst.root.dir"))
    private val runbook = File(root, "docs/runbook.md").readText()
    private val properties =
        File(
            root,
            "transport-bootstrap/src/main/kotlin/com/dynokore/gst/transport/bootstrap/ApProperties.kt",
        ).readText()

    private fun section(connection: String): String =
        runbook.substringAfter("## Connection: $connection").substringBefore("\n## ")

    private fun row(
        section: String,
        item: String,
    ): String =
        section
            .lines()
            .first { it.startsWith("| $item |") }
            .split('|')[2]
            .trim()

    @Test
    fun `INV-AC-1767 the runbook states the latency and the failure protocol and they equal the configuration`() {
        val simulator = section("certification-kit AP simulator (adapter `primary`)")
        val latency = row(simulator, "Expected acknowledgement latency")
        val protocol = row(simulator, "Failure-management protocol")
        protocol.isNotBlank() shouldBe true
        protocol shouldContain "transient failure"
        // the latency the runbook states equals the default timeout of the adapter configuration
        val seconds = Regex("^([0-9]+) seconds").find(latency)!!.groupValues[1].toLong()
        val configured = Regex("DEFAULT_TIMEOUT_SECONDS = ([0-9]+)L").find(properties)!!.groupValues[1].toLong()
        Duration.ofSeconds(seconds) shouldBe Duration.ofSeconds(configured)
        val window =
            Regex(
                "metricsWindow: Duration = Duration.ofHours\\(([0-9]+)\\)",
            ).find(properties)!!.groupValues[1].toLong()
        row(simulator, "Failure rate window") shouldContain "$window hour"
    }

    @Test
    fun `the application yaml does not set a different timeout than the runbook`() {
        val yaml =
            Yaml().load<Map<String, Any?>>(
                File(root, "transport-bootstrap/src/main/resources/application.yaml").readText(),
            )
        val ap = ((yaml["transport"] as? Map<*, *>)?.get("ap")) as? Map<*, *>
        (ap?.get("timeout")) shouldBe null
    }
}
