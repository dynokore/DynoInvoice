package com.dynokore.gst.controlPlane.adapters.seed

import tools.jackson.databind.JsonNode
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The seed code catalogue pack of INV-T-11628 and the pure functions its gates run on (INV-AC-2431, INV-AC-10207). The
 * gates take the texts as arguments, so a probe mutates a copy and never the shipped file. The source-table helpers
 * (SourceRow, leaves, rows) are those of the calendar seed.
 */
object CodeCatalogueSeedFiles {
    private const val BASE = "packs/code-catalogue/sg-gst/"
    val mapper = CalendarSeedFiles.mapper

    /** The 35 codes of INV-BR-1470 (R1 Annex E), written out here as the oracle of the set-equality gate. */
    val EXPECTED_CODES: Set<String> =
        (
            "SR SRCA-S SRCA-C SRLVG NA SRRC SROVR-RS SROVR-LVG DS ZR ES33 ESN33 OS NG TX IM ME IGDS TXCA TXNA " +
                "TXRC-TS TX-ESS TXRC-ESS IM-ESS TX-N33 TXRC-N33 IM-N33 TX-RE TXRC-RE IM-RE ZP BL EP OP NR"
        ).split(" ").toSet()

    /** The codes R1 gives a submission rate for (INV-Q-11824): section 11.19 for NA and TXNA, Example 26 for SRCA-S. */
    val SUBMISSION_CODES: Set<String> = setOf("NA", "TXNA", "SRCA-S")

    const val EFFECTIVE_FROM = "2024-01-01"
    private val CODE = Regex("^[A-Z][A-Z0-9]{0,9}(-[A-Z0-9]{1,9})?$")
    private val RATE = Regex("^(NA|[0-9]{1,3}(\\.[0-9]{1,4})?)$")
    private val PAGE = Regex("\\bpage ([0-9]+)\\b")
    private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    private fun resource(name: String): String =
        checkNotNull(
            CodeCatalogueSeedFiles::class.java.classLoader.getResource(BASE + name),
        ) { "missing resource $BASE$name" }
            .readText(Charsets.UTF_8)

    val contents: String get() = resource("contents.json")
    val sources: String get() = resource("sources.tsv")
    val release: String get() = resource("release.json")
    val releaseSources: String get() = resource("release-sources.tsv")

    /** The code catalogue contents schema of the pinned platform-contracts bundle, staged by control-plane-contract. */
    val schemaFile: File
        get() =
            File(checkNotNull(System.getProperty("gst.contracts.bundle.dir")) { "gst.contracts.bundle.dir is not set" })
                .resolve("rules/schemas/code-catalogue-pack-contents.schema.json")
                .also { check(it.isFile) { "the staged platform-contracts bundle holds no $it" } }

    /** Every problem of the catalogue text; each message starts with the code (or /complete) it is about. */
    fun codeProblems(text: String): List<String> {
        val root = mapper.readTree(text)
        val entries = root.path("codes").items()
        val codes = entries.map { it.path("code").asString("") }
        return listOfNotNull(
            "/complete: the completeness flag is not true".takeIf { !root.path("complete").asBoolean(false) },
        ) +
            EXPECTED_CODES.filter { it !in codes }.map { "$it: the code is missing from the catalogue" } +
            codes.filter { it !in EXPECTED_CODES }.map { "$it: the code is not one of the 35 Annex E codes" } +
            codes.groupBy { it }.filter { it.value.size > 1 }.map { "${it.key}: the code repeats" } +
            entries.flatMap { entryProblems(it) }
    }

    private fun entryProblems(entry: JsonNode): List<String> {
        val code = entry.path("code").asString("")
        val rate = entry.path("annexERate")
        val page =
            PAGE
                .find(entry.path("sourceReference").asString(""))
                ?.groupValues
                ?.get(1)
                ?.toInt()
        val submission = entry.path("submissionRate")
        return listOfNotNull(
            "$code: the code does not match the bare pattern".takeIf { !CODE.matches(code) },
            "$code: sourceClass is not PRIMARY".takeIf { entry.path("sourceClass").asString("") != "PRIMARY" },
            "$code: sourceReference names no page from 79 to 83".takeIf { page == null || page !in 79..83 },
            "$code: annexERate is not a decimal or NA".takeIf { !rate.isString || !RATE.matches(rate.asString()) },
            "$code: submissionRate is not \"0\" (R1 gives one for NA, TXNA and SRCA-S)"
                .takeIf { code in SUBMISSION_CODES && !(submission.isString && submission.asString() == "0") },
            "$code: submissionRate is present, but no source gives one"
                .takeIf { code !in SUBMISSION_CODES && !submission.isMissingNode },
        )
    }

    /** Every problem of release.json and release-sources.tsv; a null text is a deleted file. */
    fun releaseProblems(
        releaseText: String?,
        tsv: String?,
    ): List<String> {
        val problems = ArrayList<String>()
        if (releaseText == null) problems += "release.json: the file is missing"
        if (tsv == null) problems += "release-sources.tsv: the file is missing"
        if (releaseText != null) {
            val root = mapper.readTree(releaseText)
            val value = root.path("effectiveFrom")
            if (!value.isString || value.asString() != EFFECTIVE_FROM) {
                problems += "/effectiveFrom: release.json does not hold the string $EFFECTIVE_FROM (INV-Q-11826)"
            }
            if (root.properties().map { it.key } != listOf("effectiveFrom")) {
                problems += "release.json: the only property is effectiveFrom"
            }
        }
        if (tsv != null) {
            val rows = CalendarSeedFiles.rows(tsv, problems).filter { it.path == "/effectiveFrom" }
            val row = rows.singleOrNull()
            if (row == null) {
                problems += "/effectiveFrom: release-sources.tsv holds ${rows.size} rows for the path, expected 1"
            } else {
                problems +=
                    listOfNotNull(
                        "/effectiveFrom: the row kind '${row.kind}' is not LAW".takeIf { row.kind != "LAW" },
                        "/effectiveFrom: the row class '${row.sourceClass}' is not PRIMARY".takeIf {
                            row.sourceClass != "PRIMARY"
                        },
                        "/effectiveFrom: the row citation is empty".takeIf { row.citation.isBlank() },
                        "/effectiveFrom: the row retrieval date '${row.retrievedOn}' is not an ISO date".takeIf {
                            !isoDate(row.retrievedOn)
                        },
                    )
            }
        }
        return problems
    }

    private fun isoDate(text: String) =
        DATE.matches(text) &&
            try {
                LocalDate.parse(text)
                true
            } catch (_: DateTimeParseException) {
                false
            }
}
