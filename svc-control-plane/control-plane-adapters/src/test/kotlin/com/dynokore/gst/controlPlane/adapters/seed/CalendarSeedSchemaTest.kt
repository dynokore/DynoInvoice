package com.dynokore.gst.controlPlane.adapters.seed

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.time.LocalDate

/**
 * The seed calendar pack against the schema of the pinned platform-contracts bundle, the rules a schema cannot say
 * (the decoder rules of the schema description), INV-AC-10438 (holidays, no pinned rule, no date literal in code) and
 * INV-AC-10439 (thresholds and margin are data the pack reader returns). INV-AC-1727 names the gate.
 */
class CalendarSeedSchemaTest {
    private val root get() = CalendarSeedFiles.mapper.readTree(CalendarSeedFiles.contents) as ObjectNode

    private fun schemaErrors(text: String) =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(CalendarSeedFiles.schemaFile.readText())
            .validate(text, InputFormat.JSON)
            .map { it.message }

    private fun decoderRules(pack: JsonNode): List<String> {
        val forms =
            pack
                .path("dueRules")
                .properties()
                .map { it.key }
                .toSet()
        val windows = pack.path("windows")
        val frequencies = windows.properties().map { it.key }.toSet()
        return windows.properties().flatMap { windowProblems(it.key, it.value.items(), forms) } +
            listOfNotNull(
                "finalReturnForm has no due rule".takeIf { pack.path("finalReturnForm").asString() !in forms },
                "weekend holds every day of the week".takeIf { pack.path("weekend").size() >= 7 },
                "alertThresholds keys differ from the frequency keys of windows"
                    .takeIf {
                        pack
                            .path("alertThresholds")
                            .properties()
                            .map { it.key }
                            .toSet() != frequencies
                    },
            ) +
            pack.path("alertThresholds").properties().mapNotNull { (freq, list) ->
                "alertThresholds/$freq do not fall strictly".takeIf {
                    list.items().map { it.asInt() }.zipWithNext().any {
                        it.first <=
                            it.second
                    }
                }
            }
    }

    private fun windowProblems(
        freq: String,
        list: List<JsonNode>,
        forms: Set<String>,
    ): List<String> {
        val spans =
            list.map {
                LocalDate.parse(it.path("start").asString()) to
                    LocalDate.parse(it.path("end").asString())
            }
        return spans.filter { it.second.isBefore(it.first) }.map { "windows/$freq: a window ends before it starts" } +
            spans.sortedBy { it.first }.zipWithNext().filter { !it.second.first.isAfter(it.first.second) }.map {
                "windows/$freq: windows overlap at ${it.second.first}"
            } +
            list
                .filter {
                    it
                        .path(
                            "returnForm",
                        ).asString() !in forms
                }.map { "windows/$freq: a return form has no due rule" }
    }

    @Test
    fun `INV-AC-1727 the seed contents validate against the pinned calendar schema and the decoder rules`() {
        schemaErrors(CalendarSeedFiles.contents).shouldBeEmpty()
        decoderRules(root).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-1727 the schema check fails closed on a pack that breaks it`() {
        val broken = (root.deepCopy() as ObjectNode)
        broken.remove("finalReturnForm")
        schemaErrors(broken.toString()).isEmpty() shouldBe false
        val overlap = CalendarSeedFiles.contents.replaceFirst("\"2025-03-31\"", "\"2025-04-01\"")
        decoderRules(CalendarSeedFiles.mapper.readTree(overlap)).isEmpty() shouldBe false
    }

    @Test
    fun `INV-AC-1727 pins no rule and holds unique holidays and the frequencies of the owner ruling`() {
        root.has("pinnedRule") shouldBe false
        root.has("transition") shouldBe false
        val holidays = root.path("holidays").items().map { it.asString() }
        holidays.isEmpty() shouldBe false
        holidays.toSet().size shouldBe holidays.size
        holidays.forEach { LocalDate.parse(it) }
        root
            .path("windows")
            .properties()
            .map { it.key }
            .toSet() shouldBe setOf("QUARTERLY", "MONTHLY")
    }

    @Test
    fun `INV-AC-1727 no main source outside resources packs holds a holiday date as a literal`() {
        val dates = root.path("holidays").items().map { it.asString() }
        val hits = ArrayList<String>()
        for (module in CalendarNoCodeScan.MODULES) {
            val main = CalendarSeedFiles.repoRoot.resolve("$module/src/main")
            if (!main.isDirectory) continue
            main
                .walkTopDown()
                .filter { it.isFile && !it.invariantPath().contains("/src/main/resources/packs/") }
                .forEach { f ->
                    val text = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                    dates.filter { text.contains(it) }.forEach { hits += "${f.name}: $it" }
                }
        }
        hits.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-1727 the pack reader returns the scratch value for a scratch copy and the seeded value for the seed`() {
        val seed = CalendarPackReader(CalendarSeedFiles.contents)
        seed.frequencies.forEach { seed.alertThresholds(it).isEmpty() shouldBe false }
        seed.alertThresholds("QUARTERLY") shouldBe listOf(10)
        seed.alertThresholds("MONTHLY") shouldBe listOf(5)
        seed.safetyMargin() shouldBe (3 to "DAYS")
        val edited = (root.deepCopy() as ObjectNode)
        (edited.path("alertThresholds").get("QUARTERLY") as ArrayNode).removeAll().add(9)
        val scratch = edited.toString()
        val changed = CalendarPackReader(scratch)
        changed.alertThresholds("QUARTERLY") shouldBe listOf(9)
        changed.alertThresholds("MONTHLY") shouldBe listOf(5)
        seed.alertThresholds("QUARTERLY") shouldBe listOf(10)
    }

    private fun File.invariantPath() = path.replace(File.separatorChar, '/')
}
