package com.dynokore.gst.controlPlane.adapters.seed

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

/** The elements of an array node as a list (Jackson 3 gives JsonNode its own map). */
fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()

/** One row of sources.tsv: the authoring record of one leaf of the seed calendar pack contents. */
data class SourceRow(
    val path: String,
    val value: String,
    val kind: String,
    val sourceClass: String,
    val citation: String,
    val retrievedOn: String,
)

/**
 * The seed calendar pack of INV-T-5056 and the pure functions its gates run on (INV-AC-1727, INV-AC-10438,
 * INV-AC-10439). The gates take the two texts as arguments, so a probe mutates a copy and never the shipped file.
 */
object CalendarSeedFiles {
    private const val BASE = "packs/calendar/sg-gst/"
    const val HEADER = "path\tvalue\tsource kind\tsource class\tcitation\tretrieved on"
    val mapper: JsonMapper = JsonMapper.builder().build()

    private fun resource(name: String): String =
        checkNotNull(
            CalendarSeedFiles::class.java.classLoader.getResource(BASE + name),
        ) { "missing resource $BASE$name" }
            .readText(Charsets.UTF_8)

    val contents: String get() = resource("contents.json")
    val sources: String get() = resource("sources.tsv")

    /** The repository root, set by control-plane-adapters/build.gradle.kts. */
    val repoRoot: File get() = File(checkNotNull(System.getProperty("gst.repo.root")) { "gst.repo.root is not set" })

    /** The calendar contents schema of the pinned platform-contracts bundle, staged by control-plane-contract. */
    val schemaFile: File
        get() =
            File(checkNotNull(System.getProperty("gst.contracts.bundle.dir")) { "gst.contracts.bundle.dir is not set" })
                .resolve("rules/schemas/calendar-pack-contents.schema.json")
                .also { check(it.isFile) { "the staged platform-contracts bundle holds no $it" } }

    private fun escape(token: String) = token.replace("~", "~0").replace("/", "~1")

    /** JSON Pointer (RFC 6901) to JSON text of every leaf of [root], except the leaves under /entrySources. */
    fun leaves(root: JsonNode): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        fun walk(
            node: JsonNode,
            pointer: String,
        ) {
            when {
                node.isObject -> node.properties().forEach { (k, v) -> walk(v, "$pointer/${escape(k)}") }
                node.isArray -> node.forEachIndexed { i, v -> walk(v, "$pointer/$i") }
                else -> out[pointer] = node.toString()
            }
        }
        root.properties().filter { it.key != "entrySources" }.forEach { (k, v) -> walk(v, "/${escape(k)}") }
        return out
    }

    /** Rows of sources.tsv; a malformed line is reported, never skipped. */
    fun rows(
        tsv: String,
        problems: MutableList<String>,
    ): List<SourceRow> {
        val lines = tsv.split("\n").filter { it.isNotEmpty() }
        if (lines.firstOrNull() != HEADER) problems += "sources.tsv: the header line is not '$HEADER'"
        return lines.drop(1).mapIndexedNotNull { i, line ->
            val c = line.split("\t")
            if (c.size != 6) {
                problems += "sources.tsv line ${i + 2}: ${c.size} columns, expected 6"
                null
            } else {
                SourceRow(c[0], c[1], c[2], c[3], c[4], c[5])
            }
        }
    }

    /** The entry a leaf belongs to (INV-Q-10900 A), keyed like a row of entrySources. */
    fun entryOf(
        root: JsonNode,
        pointer: String,
    ): String {
        val t = pointer.split("/").drop(1).map { it.replace("~1", "/").replace("~0", "~") }
        return when (t[0]) {
            "windows" -> "windows/${t[1]}/${root.path(
                "windows",
            ).path(t[1]).path(t[2].toInt()).path("start").asString()}"

            "dueRules" -> "dueRules/${t[1]}"

            "holidays" -> "holidays/${root.path("holidays").path(t[1].toInt()).asString()}"

            "alertThresholds" -> "alertThresholds/${t[1]}"

            "finalReturnForm", "weekend", "safetyMargin" -> t[0]

            else -> "unknown:$pointer"
        }
    }

    private val ROW_FIELDS =
        mapOf(
            "windows" to listOf("frequency", "date"),
            "dueRules" to listOf("returnForm"),
            "holidays" to listOf("date"),
            "alertThresholds" to listOf("frequency"),
            "finalReturnForm" to emptyList(),
            "weekend" to emptyList(),
            "safetyMargin" to emptyList(),
            "pinnedRule" to emptyList(),
        )

    /** The entry key a row of entrySources names, or null when the row is not well formed. */
    fun entryOfRow(row: JsonNode): String? {
        fun text(name: String) =
            row
                .path(name)
                .takeIf { it.isString }
                ?.asString()
                ?.takeIf { it.isNotEmpty() }
        val section = text("section")
        val fields = ROW_FIELDS[section]?.map { text(it) }
        return if (section == null || fields == null || fields.any { it == null }) {
            null
        } else {
            (listOf(section) + fields).joinToString("/")
        }
    }
}

/** The pack reader of the resolution test (INV-AC-10439): every value comes from the contents text, none from code. */
class CalendarPackReader(
    contentsText: String,
) {
    private val root = CalendarSeedFiles.mapper.readTree(contentsText)

    val frequencies: List<String> get() = root.path("windows").properties().map { it.key }

    fun alertThresholds(frequency: String): List<Int> =
        root
            .path("alertThresholds")
            .path(frequency)
            .items()
            .map { it.asInt() }

    fun safetyMargin(): Pair<Int, String> =
        root.path("safetyMargin").let { it.path("value").asInt() to it.path("unit").asString() }
}
