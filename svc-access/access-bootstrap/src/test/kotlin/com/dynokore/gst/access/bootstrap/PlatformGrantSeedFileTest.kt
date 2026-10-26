package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-AC-9279 (INV-REQ-862 behavior 4, INV-Q-9073 C, INV-Q-11225 C): the seed file of operator grants equals, by
 * equality and in order, the rows embedded in the seed migration, and names only the six operator-role wire names.
 */
class PlatformGrantSeedFileTest {
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "$path is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val tsv = resource(SEED_FILE)
    private val migration = resource(MIGRATION)

    /** The differences between a seed file and the rows embedded in a migration; empty when they agree. */
    private fun findings(
        tsv: String,
        migration: String,
    ): List<String> {
        val found = mutableListOf<String>()
        val fileRows = fileRows(tsv, found)
        fileRows.filter { it.second !in ROLES }.forEach { found += "role '${it.second}' is not a wire name" }
        fileRows
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { found += "row $it repeats" }
        val embedded = embeddedRows(migration, found)
        if (embedded != null && embedded != fileRows) found += "file rows $fileRows differ from embedded $embedded"
        return found
    }

    private fun fileRows(
        tsv: String,
        found: MutableList<String>,
    ): List<Pair<String, String>> {
        val lines = tsv.split("\n")
        if (lines.first() != HEADER) found += "the header is not identity_ref<TAB>role"
        if (lines.last() != "") found += "the file does not end with one line feed"
        return lines
            .drop(1)
            .dropLast(1)
            .map { it.split("\t") }
            .onEach { if (it.size != 2 || it[0].isEmpty()) found += "malformed row $it" }
            .filter { it.size == 2 }
            .map { it[0] to it[1] }
    }

    /** The rows between the two marker lines, or null when the markers are not exactly one pair in order. */
    private fun embeddedRows(
        migration: String,
        found: MutableList<String>,
    ): List<Pair<String, String>>? {
        val lines = migration.split("\n")
        val begin = lines.count { it == BEGIN }
        val end = lines.count { it == END }
        if (begin != 1 || end != 1) found += "marker lines: $begin begin, $end end"
        if (begin != 1 || end != 1 || lines.indexOf(END) < lines.indexOf(BEGIN)) return null
        return lines.subList(lines.indexOf(BEGIN) + 1, lines.indexOf(END)).mapNotNull { line ->
            ROW.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2] }.also {
                if (it == null) found += "embedded line '$line' is not a row"
            }
        }
    }

    private fun migrationWith(vararg rows: Pair<String, String>): String =
        migration.replace(
            "\n$BEGIN\n",
            "\n$BEGIN\n" + rows.joinToString("") { "    ('${it.first}', '${it.second}'),\n" },
        )

    private fun fileWith(vararg rows: Pair<String, String>): String =
        HEADER + "\n" + rows.joinToString("") { "${it.first}\t${it.second}\n" }

    private val pair = arrayOf("alice" to "platform.support", "bob" to "release.manager")

    @Test
    fun `INV-AC-9279 the shipped seed file and the shipped migration agree and hold no data row`() {
        assertThat(findings(tsv, migration)).isEmpty()
        assertThat(tsv).isEqualTo(HEADER + "\n")
    }

    @Test
    fun `INV-AC-9279 a two-row seed file equal to a two-row copy of the migration passes`() {
        assertThat(findings(fileWith(*pair), migrationWith(*pair))).isEmpty()
    }

    @Test
    fun `INV-AC-9279 probe - an added row in the file, or a removed row, fails`() {
        assertThat(findings(fileWith(*pair, "carol" to "platform.engineer"), migrationWith(*pair))).isNotEmpty()
        assertThat(findings(fileWith(pair[0]), migrationWith(*pair))).isNotEmpty()
    }

    @Test
    fun `INV-AC-9279 probe - a role that is not one of the six wire names fails`() {
        listOf("tenant.admin", "deployment.operator", "platform.support ", "Platform.Support", "").forEach { role ->
            assertThat(findings(fileWith("alice" to role), migrationWith("alice" to role)))
                .describedAs("role '$role'")
                .isNotEmpty()
        }
    }

    @Test
    fun `INV-AC-9279 each of the six wire names passes`() {
        ROLES.forEach { role ->
            assertThat(findings(fileWith("alice" to role), migrationWith("alice" to role))).describedAs(role).isEmpty()
        }
    }

    @Test
    fun `INV-AC-9279 probe - a repeated row fails`() {
        assertThat(findings(fileWith(pair[0], pair[0]), migrationWith(pair[0], pair[0]))).isNotEmpty()
    }

    @Test
    fun `INV-AC-9279 probe - a changed header fails`() {
        assertThat(
            findings(fileWith(*pair).replaceFirst("identity_ref", "identity"), migrationWith(*pair)),
        ).isNotEmpty()
        assertThat(findings(fileWith(*pair).replaceFirst("\t", " "), migrationWith(*pair))).isNotEmpty()
    }

    @Test
    fun `INV-AC-9279 probe - two begin markers, or no end marker, fail`() {
        assertThat(findings(tsv, migration.replace("\n$BEGIN\n", "\n$BEGIN\n$BEGIN\n"))).isNotEmpty()
        assertThat(findings(tsv, migration.replace("\n$END\n", "\n"))).isNotEmpty()
        assertThat(findings(tsv, migration.replace("\n$END\n", "\n$END\n$END\n"))).isNotEmpty()
        assertThat(findings(tsv, migration.replace("\n$BEGIN\n", "\n$BEGIN \n"))).isNotEmpty()
    }

    @Test
    fun `INV-AC-9279 probe - an embedded line that is not a row fails`() {
        assertThat(findings(tsv, migration.replace("\n$BEGIN\n", "\n$BEGIN\n    ('a', 'b', 'c'),\n"))).isNotEmpty()
    }

    @Test
    fun `INV-AC-9279 probe - a look-alike seed file is not on the classpath`() {
        assertThat(javaClass.classLoader.getResource("$SEED_FILE.bak")).isNull()
        assertThat(javaClass.classLoader.getResource("db/seed/Platform-operator-grants.tsv")).isNull()
    }

    private companion object {
        const val SEED_FILE = "db/seed/platform-operator-grants.tsv"
        const val MIGRATION = "db/migration/V202610280900__seed_platform_operator_grants.sql"
        const val HEADER = "identity_ref\trole"
        const val BEGIN = "-- seed rows begin"
        const val END = "-- seed rows end"
        val ROW = Regex("""    \('([^']+)', '([^']*)'\),""")
        val ROLES =
            listOf(
                "platform.support",
                "platform.engineer",
                "release.manager",
                "commercial.admin",
                "regulatory.author",
                "regulatory.approver",
            )
    }
}
