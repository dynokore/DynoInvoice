package com.dynokore.gst.controlPlane.adapters.seed

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Gates (codes and release date, fail closed; INV-AC-2431, INV-AC-10207): the shipped catalogue holds the 35 Annex E
 * codes by set equality, and each probe, run once on a copy, fails naming the code or path.
 */
class CodeCatalogueSeedCodesTest {
    private fun edited(edit: (ArrayNode) -> Unit): String {
        val root = CodeCatalogueSeedFiles.mapper.readTree(CodeCatalogueSeedFiles.contents) as ObjectNode
        edit(root.path("codes") as ArrayNode)
        return root.toString()
    }

    private fun entry(
        codes: ArrayNode,
        code: String,
    ): ObjectNode = codes.items().first { it.path("code").asString() == code } as ObjectNode

    private fun named(
        text: String,
        name: String,
    ) = CodeCatalogueSeedFiles.codeProblems(text).any { it.startsWith("$name:") } shouldBe true

    private fun codeList(root: JsonNode) = root.path("codes").items().map { it.path("code").asString() }

    @Test
    fun `INV-AC-2431 the shipped catalogue holds the 35 Annex E codes, PRIMARY, complete`() {
        CodeCatalogueSeedFiles.codeProblems(CodeCatalogueSeedFiles.contents).shouldBeEmpty()
        val root = CodeCatalogueSeedFiles.mapper.readTree(CodeCatalogueSeedFiles.contents)
        codeList(root).toSet() shouldBe CodeCatalogueSeedFiles.EXPECTED_CODES
        codeList(root).size shouldBe 35
        root.path("complete").asBoolean() shouldBe true
        listOf("SRCA-C", "SRRC", "DS", "OS").forEach { code ->
            root
                .path("codes")
                .items()
                .first { it.path("code").asString() == code }
                .path("sourceClass")
                .asString() shouldBe "PRIMARY"
        }
    }

    @Test
    fun `INV-AC-10207 NA, TXNA and SRCA-S carry the submission rate 0 and no other entry carries one`() {
        val root = CodeCatalogueSeedFiles.mapper.readTree(CodeCatalogueSeedFiles.contents)
        val with =
            root
                .path("codes")
                .items()
                .filter { it.has("submissionRate") }
                .associate { it.path("code").asString() to it.path("submissionRate").asString() }
        with shouldBe mapOf("NA" to "0", "TXNA" to "0", "SRCA-S" to "0")
    }

    @Test
    fun `INV-AC-2431 probe delete ZR fails naming ZR`() {
        named(edited { c -> c.remove(c.items().indexOfFirst { it.path("code").asString() == "ZR" }) }, "ZR")
    }

    @Test
    fun `INV-AC-2431 probe add SRX fails naming SRX`() {
        named(edited { it.addObject().put("code", "SRX") }, "SRX")
    }

    @Test
    fun `INV-AC-2431 probe rename SR to sr fails naming sr and SR`() {
        val text = edited { entry(it, "SR").put("code", "sr") }
        named(text, "sr")
        named(text, "SR")
    }

    @Test
    fun `INV-AC-10207 probe duplicate TX fails naming TX`() {
        named(edited { it.add(entry(it, "TX").deepCopy()) }, "TX")
    }

    @Test
    fun `INV-AC-2431 probe set ZR to SECONDARY fails naming ZR`() {
        named(edited { entry(it, "ZR").put("sourceClass", "SECONDARY") }, "ZR")
    }

    @Test
    fun `INV-AC-10207 probe drop the submissionRate of NA fails naming NA`() {
        named(edited { entry(it, "NA").remove("submissionRate") }, "NA")
    }

    @Test
    fun `INV-AC-10207 probe drop the submissionRate of SRCA-S fails naming SRCA-S`() {
        named(edited { entry(it, "SRCA-S").remove("submissionRate") }, "SRCA-S")
    }

    @Test
    fun `INV-AC-10207 probe add a submissionRate 0 to ES33 fails naming ES33`() {
        named(edited { entry(it, "ES33").put("submissionRate", "0") }, "ES33")
    }

    @Test
    fun `INV-AC-2431 probe set complete false fails naming the flag`() {
        val root = CodeCatalogueSeedFiles.mapper.readTree(CodeCatalogueSeedFiles.contents) as ObjectNode
        named(root.put("complete", false).toString(), "/complete")
    }

    @Test
    fun `INV-AC-10207 probe a page outside 79 to 83 or no page fails naming the code`() {
        named(edited { entry(it, "SR").put("sourceReference", "R1 Annex E, page 78") }, "SR")
        named(edited { entry(it, "SR").put("sourceReference", "R1 Annex E") }, "SR")
        named(edited { entry(it, "SR").put("sourceReference", "") }, "SR")
    }

    @Test
    fun `INV-AC-10207 probe an annexERate of 9 percent or a number fails naming the code`() {
        named(edited { entry(it, "SR").put("annexERate", "9%") }, "SR")
        named(edited { entry(it, "SR").put("annexERate", 9) }, "SR")
    }

    @Test
    fun `INV-AC-2431 look-alike the same entries in another order pass`() {
        val text =
            edited { codes ->
                val list = codes.items().reversed()
                codes.removeAll()
                list.forEach { codes.add(it) }
            }
        CodeCatalogueSeedFiles.codeProblems(text).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-2431 look-alike an unknown extra property on an entry passes`() {
        CodeCatalogueSeedFiles.codeProblems(edited { entry(it, "SR").put("future", "x") }).shouldBeEmpty()
    }

    private fun releaseWith(
        value: String,
        order: Boolean = false,
    ) = if (order) """{"x":1,"effectiveFrom":"$value"}""" else """{"effectiveFrom":"$value"}"""

    private fun releaseNamed(
        problems: List<String>,
        name: String,
    ) = problems.any { it.startsWith("$name:") } shouldBe true

    private fun tsvRow(edit: (MutableList<String>) -> Unit): String =
        CodeCatalogueSeedFiles.releaseSources.split("\n").joinToString("\n") { line ->
            val c = line.split("\t").toMutableList()
            if (c[0] == "/effectiveFrom") edit(c)
            c.joinToString("\t")
        }

    @Test
    fun `INV-AC-2431 the shipped release date is 2024-01-01 with a LAW PRIMARY row`() {
        CodeCatalogueSeedFiles
            .releaseProblems(CodeCatalogueSeedFiles.release, CodeCatalogueSeedFiles.releaseSources)
            .shouldBeEmpty()
    }

    @Test
    fun `INV-AC-2431 probe effectiveFrom 2024-01-02, 2023-01-01, 2024-1-1 and 2026-11-01 each fail naming the path`() {
        listOf("2024-01-02", "2023-01-01", "2024-1-1", "2026-11-01").forEach {
            releaseNamed(
                CodeCatalogueSeedFiles.releaseProblems(releaseWith(it), CodeCatalogueSeedFiles.releaseSources),
                "/effectiveFrom",
            )
        }
    }

    @Test
    fun `INV-AC-2431 probe a bad class, citation or date in the release row fails naming the path`() {
        listOf<(MutableList<String>) -> Unit>(
            { it[3] = "SECONDARY" },
            { it[4] = "" },
            { it[2] = "REQUIREMENT" },
            { it[5] = "2026-13-40" },
            { it[5] = "" },
        ).forEach { edit ->
            releaseNamed(
                CodeCatalogueSeedFiles.releaseProblems(CodeCatalogueSeedFiles.release, tsvRow(edit)),
                "/effectiveFrom",
            )
        }
    }

    @Test
    fun `INV-AC-2431 probe a deleted row, release_json and release-sources_tsv fail naming the path or file`() {
        val header = CodeCatalogueSeedFiles.releaseSources.lines().first() + "\n"
        releaseNamed(CodeCatalogueSeedFiles.releaseProblems(CodeCatalogueSeedFiles.release, header), "/effectiveFrom")
        releaseNamed(
            CodeCatalogueSeedFiles.releaseProblems(null, CodeCatalogueSeedFiles.releaseSources),
            "release.json",
        )
        releaseNamed(
            CodeCatalogueSeedFiles.releaseProblems(CodeCatalogueSeedFiles.release, null),
            "release-sources.tsv",
        )
    }

    @Test
    fun `INV-AC-2431 look-alike release_json with the properties in another order passes the date check`() {
        val text = """{"effectiveFrom":"2024-01-01"}"""
        CodeCatalogueSeedFiles.releaseProblems(text, CodeCatalogueSeedFiles.releaseSources).shouldBeEmpty()
        releaseNamed(
            CodeCatalogueSeedFiles.releaseProblems(
                releaseWith("2024-01-01", true),
                CodeCatalogueSeedFiles.releaseSources,
            ),
            "release.json",
        )
    }
}
