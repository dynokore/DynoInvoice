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

    // ---- runbook lint (INV-AC-18633, INV-AC-18635): pure functions over the runbook text, probed on altered copies

    private val configuredSeconds =
        Regex("DEFAULT_TIMEOUT_SECONDS = ([0-9]+)L").find(properties)!!.groupValues[1].toLong()

    private fun lintSources(text: String): List<String> {
        val findings = mutableListOf<String>()
        val body = text.substringAfter("\n## Sources", "").substringBefore("\n## ")
        if (body.isBlank()) return listOf("section Sources is missing")
        val rows =
            body.lines().filter { it.startsWith("| ") && !it.startsWith("| Source |") && !it.startsWith("| ---") }

        fun check(
            name: String,
            key: String,
        ) {
            val r = rows.firstOrNull { it.contains(key) }
            if (r == null) {
                findings += "source missing: $name"
                return
            }
            val version =
                r
                    .split('|')
                    .getOrNull(2)
                    ?.trim()
                    .orEmpty()
            if (version.isEmpty()) {
                findings += "version missing: $name"
            } else if (!version.contains("`provisional`")) {
                findings += "provisional marker missing on version: $name"
            }
        }
        check("Invoice Data Submission API Interface Specs (R3)", "Invoice Data Submission API Interface Specs")
        check("InvoiceNow Technical Playbook", "InvoiceNow Technical Playbook")
        if (!Regex("obtained through the Access Point").containsMatchIn(body)) {
            findings += "route missing: the Access Point is not named as the route the documents are obtained through"
        }
        return findings
    }

    private fun lintRecords(text: String): List<String> {
        val sections = text.split(Regex("(?m)^## ")).filter { it.startsWith("Connection: ") }
        if (sections.isEmpty()) return listOf("no connection section")
        return sections.flatMap { lintConnection(it) }
    }

    private fun rowValue(
        section: String,
        item: String,
    ): String? =
        section
            .lines()
            .firstOrNull { it.startsWith("| $item |") }
            ?.split('|')
            ?.get(2)
            ?.trim()

    private fun lintConnection(section: String): List<String> {
        val findings = mutableListOf<String>()
        val name =
            section
                .lineSequence()
                .first()
                .removePrefix("Connection: ")
                .trim()
        val latency = rowValue(section, "Expected acknowledgement latency")
        val protocol = rowValue(section, "Failure-management protocol")
        if (latency == null) {
            findings += "connection $name: latency row missing"
        } else {
            val seconds =
                Regex("^([0-9]+) seconds")
                    .find(latency)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            if (seconds != configuredSeconds) {
                findings +=
                    "connection $name: latency value '$latency' differs from configured $configuredSeconds seconds"
            }
            if (!latency.contains("`provisional`")) {
                findings += "connection $name: provisional marker missing on latency"
            }
        }
        if (protocol == null) {
            findings += "connection $name: failure protocol row missing"
        } else {
            if (!protocol.contains("transient failure")) findings += "connection $name: failure protocol value changed"
            if (!protocol.contains("`provisional`")) {
                findings += "connection $name: provisional marker missing on protocol"
            }
        }
        return findings
    }

    private fun without(
        prefix: String,
        text: String = runbook,
    ): String = text.lines().filterNot { it.startsWith(prefix) }.joinToString("\n")

    private fun mustChange(
        original: String,
        from: String,
        to: String,
    ): String {
        check(original.contains(from)) { "probe target absent: $from" }
        return original.replaceFirst(from, to)
    }

    @Test
    fun `INV-AC-18633 the committed runbook cites both sources, provisional, and names the Access Point route`() {
        lintSources(runbook) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-18633 the R3 citation removed fails naming the source`() {
        lintSources(without("| Invoice Data Submission API Interface Specs")) shouldBe
            listOf("source missing: Invoice Data Submission API Interface Specs (R3)")
    }

    @Test
    fun `INV-AC-18633 the Technical Playbook citation removed fails naming the source`() {
        lintSources(without("| InvoiceNow Technical Playbook")) shouldBe
            listOf("source missing: InvoiceNow Technical Playbook")
    }

    @Test
    fun `INV-AC-18633 one version value removed fails naming the version`() {
        val altered =
            mustChange(
                runbook,
                "| InvoiceNow Technical Playbook (IMDA) | not yet obtained (`provisional`) |",
                "| InvoiceNow Technical Playbook (IMDA) |  |",
            )
        lintSources(altered) shouldBe listOf("version missing: InvoiceNow Technical Playbook")
    }

    @Test
    fun `INV-AC-18633 a version without the provisional marker fails`() {
        val altered = mustChange(runbook, "| not yet obtained (`provisional`) |", "| not yet obtained |")
        lintSources(altered) shouldBe
            listOf("provisional marker missing on version: Invoice Data Submission API Interface Specs (R3)")
    }

    @Test
    fun `INV-AC-18633 the route sentence removed fails naming the route`() {
        val altered =
            mustChange(runbook, "Both documents are obtained through the Access Point, which already holds both.", "")
        lintSources(altered).single() shouldContain "route missing"
    }

    @Test
    fun `INV-AC-18635 the committed runbook records latency and protocol per connection, provisional and equal`() {
        lintRecords(runbook) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-18635 one latency row deleted fails naming the connection`() {
        val name = "certification-kit AP simulator (adapter `primary`)"
        lintRecords(without("| Expected acknowledgement latency")) shouldBe
            listOf("connection $name: latency row missing")
    }

    @Test
    fun `INV-AC-18635 one provisional marker removed fails naming the connection`() {
        val name = "certification-kit AP simulator (adapter `primary`)"
        val altered = mustChange(runbook, "`transport.ap.timeout`) (`provisional`) |", "`transport.ap.timeout`) |")
        lintRecords(altered) shouldBe listOf("connection $name: provisional marker missing on latency")
    }

    @Test
    fun `INV-AC-18635 one recorded value changed fails naming the connection and the value`() {
        val name = "certification-kit AP simulator (adapter `primary`)"
        val altered = mustChange(runbook, "| 25 seconds", "| 26 seconds")
        lintRecords(altered).single() shouldContain "connection $name: latency value '26 seconds"
    }

    @Test
    fun `INV-AC-1667 the runbook cites R3 and the Technical Playbook by version through the Access Point`() {
        lintSources(runbook) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1852 the runbook records the acknowledgement latency and the failure protocol of each connection`() {
        lintRecords(runbook) shouldBe emptyList()
    }
}
