package com.dynokore.platform.serviceruntime.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import gst.build.konsist.MigrationLint
import gst.build.konsist.RawSqlPlacement
import gst.build.konsist.SqlGuards
import gst.build.konsist.sql.GuardViolation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * platform-build's SQL guards over every platform-libs module (INV-REQ-854 b5 and b6, INV-ADR-3968 items 4a and 11).
 * Nothing is suppressed.
 *
 * - 0.9.0 (INV-T-3985; coordinator rulings INV-Q-4036, INV-Q-4040), over the production sources: `SqlGuards` (no raw
 *   jOOQ SQL, identifiers only through tenancy-kernel's `SqlIdentifier.require`, session settings only in
 *   tenancy-kernel) and `MigrationLint`'s definer, owner and immutable-table rules.
 * - 0.10.0 (INV-T-4135, INV-T-4188; coordinator ruling INV-Q-4185): `RawSqlPlacement` over the main and every test
 *   source set of every module (`Konsist.scopeFromProject()`; JDBC only in the allow-listed modules and tenancy-jooq's
 *   fixed exemption, tests only through TenancyProbe), and `MigrationLint`'s `tenant-column` rule.
 *
 * Hosted here, like `L1DependencyOrderTest`, because service-runtime is the composition root; the scope is the
 * whole repository, not this module.
 *
 * Each group has its pending list of the findings the task that rewrites a module clears ([PENDING], INV-Q-4040;
 * [PENDING_0_10], INV-T-4188). Pending findings are listed by owner on every run, never waived; any other finding
 * fails the build. Each list may only shrink: an entry may not be added (it must stay within its adoption list), and
 * an entry with no finding left fails this test until it is removed. INV-T-3999 requires both lists empty.
 */
class SqlGuardsTest {
    private val rootDir: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, "tenancy-kernel").isDirectory }

    private val allFindings: List<GuardViolation> by lazy {
        val placement = RawSqlPlacement.findViolations(Konsist.scopeFromProject())
        guardFindings(Konsist.scopeFromProduction(), rootDir) + placement
    }

    /** The 0.9.0 group's findings, by module. */
    private val findings: Map<String, List<GuardViolation>> by lazy {
        allFindings.filterNot { it.rule in RULES_0_10 }.groupBy { moduleOf(it) }
    }

    /** The 0.10.0 group's findings, by module and rule. */
    private val findings010: Map<Pair<String, String>, List<GuardViolation>> by lazy {
        allFindings.filter { it.rule in RULES_0_10 }.groupBy { moduleOf(it) to it.rule }
    }

    @Test
    fun `no module outside the pending list has a SQL guard finding`() {
        val pendingReport =
            PENDING.entries.joinToString("\n") { (module, task) ->
                val own = findings[module].orEmpty()
                "  $module (cleared by $task): ${own.size} finding(s)" +
                    own.joinToString("") { "\n    - $it" }
            }
        val cleared =
            productionModules().filterNot { it in PENDING }.joinToString("\n") { module ->
                "  $module: ${findings[module].orEmpty().size} finding(s)"
            }
        println("SQL guards, modules held to zero findings:\n$cleared")
        println("SQL guards, pending modules (INV-Q-4040, owned, not waived):\n$pendingReport")

        GuardViolation.assertNone(
            findings.filterKeys { it !in PENDING }.values.flatten(),
            "INV-REQ-854 b5 / INV-ADR-3968 item 11 SQL guards (modules outside the INV-Q-4040 pending list)",
        )
    }

    @Test
    fun `the pending list only shrinks`() {
        assertTrue(PENDING_AT_ADOPTION.keys.containsAll(PENDING.keys)) {
            "INV-Q-4040: the pending list may only shrink; added: ${PENDING.keys - PENDING_AT_ADOPTION.keys}"
        }
        PENDING.forEach { (module, task) ->
            assertEquals(PENDING_AT_ADOPTION[module], task) { "$module's clearing task is fixed by INV-Q-4040" }
        }
        val cleared = PENDING.keys.filter { findings[it].isNullOrEmpty() }
        assertTrue(cleared.isEmpty()) {
            "INV-Q-4040: $cleared have no SQL guard finding left; remove them from SqlGuardsTest.PENDING"
        }
    }

    @Test
    fun `no module outside the 0_10_0 pending list has a raw-SQL placement or tenant-column finding`() {
        val pendingReport =
            PENDING_0_10.entries.joinToString("\n") { (key, task) ->
                val own = findings010[key].orEmpty()
                "  ${key.first} [${key.second}] (cleared by $task): ${own.size} finding(s)" +
                    own.joinToString("") { "\n    - $it" }
            }
        val held = findings010.filterKeys { it !in PENDING_0_10 }
        println("0.10.0 guards (${RULES_0_10.sorted()}), every other module and rule held to zero findings")
        println("0.10.0 guards, pending entries (INV-T-4188, owned, not waived):\n$pendingReport")

        GuardViolation.assertNone(
            held.values.flatten(),
            "INV-REQ-854 b6 / INV-ADR-3968 item 4a raw-SQL placement and tenant column (outside the INV-T-4188 " +
                "pending list)",
        )
    }

    @Test
    fun `the 0_10_0 pending list only shrinks`() {
        assertTrue(PENDING_0_10_AT_ADOPTION.keys.containsAll(PENDING_0_10.keys)) {
            val added = PENDING_0_10.keys - PENDING_0_10_AT_ADOPTION.keys
            "INV-T-4188: the 0.10.0 pending list may only shrink; added: $added"
        }
        PENDING_0_10.forEach { (key, task) ->
            assertEquals(PENDING_0_10_AT_ADOPTION[key], task) { "$key's clearing task is fixed by INV-T-4188" }
        }
        PENDING_0_10.filterKeys { it.second == "jdbc" }.forEach { (key, task) ->
            assertTrue(task in LIBRARY_JDBC_TASKS) {
                "$key: library JDBC is removed by $LIBRARY_JDBC_TASKS (INV-Q-4196)"
            }
        }
        val cleared = PENDING_0_10.keys.filter { findings010[it].isNullOrEmpty() }
        assertTrue(cleared.isEmpty()) {
            "INV-T-4188: $cleared have no finding left; remove them from SqlGuardsTest.PENDING_0_10"
        }
    }

    @Test
    fun `the 0_10_0 scope covers the main and every test source set of every module`() {
        val scanned =
            Konsist
                .scopeFromProject()
                .files
                .map { File(it.path).relativeTo(rootDir).invariantSeparatorsPath }
                .filter { it.contains("/src/") }
                .map { it.substringBefore("/src/") + "/" + it.substringAfter("/src/").substringBefore('/') }
                .toSet()
        val sourceSets =
            rootDir
                .listFiles()
                .orEmpty()
                .filter { File(it, "build.gradle.kts").isFile }
                .flatMap { module ->
                    File(module, "src")
                        .listFiles()
                        .orEmpty()
                        .filter { set ->
                            set.walkTopDown().any { it.isFile && it.extension == "kt" }
                        }.map { "${module.name}/${it.name}" }
                }.toSet()
        assertTrue(sourceSets.any { it.endsWith("/test") } && sourceSets.any { it.endsWith("/main") }) {
            "expected main and test source sets, found $sourceSets"
        }
        assertEquals(emptyList<String>(), (sourceSets - scanned).sorted())
    }

    @Test
    fun `the scope covers the production sources of every module`() {
        val scanned =
            Konsist
                .scopeFromProduction()
                .files
                .map { moduleOf(it.path) }
                .toSet()
        val withProductionSources = productionModules()
        assertTrue(withProductionSources.size >= 10) {
            "expected the platform-libs modules, found $withProductionSources"
        }
        assertEquals(emptyList<String>(), withProductionSources.filterNot { it in scanned })
    }

    /** CS-50: the guards still fail on a fixture (an interpolated, unvalidated identifier and a definer grant). */
    @Test
    fun `would fail on an unvalidated identifier or a definer without a fixed search_path`(
        @TempDir dir: File,
    ) {
        val main = File(dir, "fixture-module/src/main/kotlin").apply { mkdirs() }
        File(main, "Offender.kt").writeText(
            "package com.dynokore.platform.fixture\n" +
                "import java.sql.Connection\n" +
                "class Offender(private val c: Connection) {\n" +
                "    fun purge(table: String) = c.prepareStatement(\"delete from \$table where id = ?\")\n" +
                "}\n",
        )
        val migrations = File(dir, "fixture-module/src/main/resources/db/migration").apply { mkdirs() }
        File(migrations, "V1__definer.sql").writeText(
            "CREATE FUNCTION fixture.f() RETURNS void LANGUAGE sql SECURITY DEFINER AS 'select 1';\n",
        )

        val found = guardFindings(Konsist.scopeFromExternalDirectory(dir.absolutePath), dir)
        assertTrue(found.any { it.location.endsWith("Offender.kt") }) { "SqlGuards missed the fixture: $found" }
        assertTrue(found.any { it.location.endsWith("V1__definer.sql") }) { "MigrationLint missed the fixture: $found" }
        assertThrows(AssertionError::class.java) { GuardViolation.assertNone(found, "fixture") }
    }

    /** CS-50: the 0.10.0 rules still fail on a fixture (JDBC in a test source set, a table without tenant_id). */
    @Test
    fun `would fail on JDBC in a test source set or a table without a tenant column`(
        @TempDir dir: File,
    ) {
        val test = File(dir, "fixture-module/src/test/kotlin").apply { mkdirs() }
        File(test, "OffenderTest.kt").writeText(
            "package com.dynokore.platform.fixture\n" +
                "import javax.sql.DataSource\n" +
                "class OffenderTest(private val dataSource: DataSource)\n",
        )
        val migrations = File(dir, "fixture-module/src/main/resources/db/migration").apply { mkdirs() }
        File(migrations, "V1__tenantless.sql").writeText("CREATE TABLE fixture.t (id uuid PRIMARY KEY);\n")

        val scope = Konsist.scopeFromExternalDirectory(dir.absolutePath)
        val found = RawSqlPlacement.findViolations(scope) + MigrationLint.findViolations(dir, scope)
        assertTrue(found.any { it.rule == "jdbc" && it.location.endsWith("OffenderTest.kt") }) {
            "RawSqlPlacement missed the test fixture: $found"
        }
        assertTrue(found.any { it.rule == "tenant-column" && it.location.endsWith("V1__tenantless.sql") }) {
            "MigrationLint's tenant-column rule missed the fixture: $found"
        }
    }

    /** Every module directory with Kotlin sources in a non-test source set. */
    private fun productionModules(): List<String> =
        rootDir
            .listFiles()
            .orEmpty()
            .filter { File(it, "build.gradle.kts").isFile && File(it, "src").isDirectory }
            .map { it.name }
            .sorted()
            .filter { m ->
                File(rootDir, "$m/src").listFiles().orEmpty().any { set ->
                    !set.name.contains("test", ignoreCase = true) &&
                        set.walkTopDown().any { it.isFile && it.extension == "kt" }
                }
            }

    private fun guardFindings(
        scope: KoScope,
        projectDir: File,
    ): List<GuardViolation> =
        SqlGuards.findRawJooqSql(scope) +
            SqlGuards.findInterpolatedSql(scope) +
            SqlGuards.findSessionSettings(scope) +
            MigrationLint.findViolations(projectDir, scope)

    private fun moduleOf(violation: GuardViolation): String = moduleOf(violation.location)

    private fun moduleOf(path: String): String {
        val file = File(path).let { if (it.isAbsolute) it else File(rootDir, path) }
        return file.absoluteFile
            .relativeToOrNull(rootDir)
            ?.invariantSeparatorsPath
            ?.substringBefore('/')
            ?: file.path
    }

    companion object {
        /** INV-Q-4040: the modules whose findings went to their rewriting task when the guards were adopted. */
        private val PENDING_AT_ADOPTION =
            mapOf(
                "outbox-and-queue" to "INV-T-3979",
                "service-runtime" to "INV-T-3981",
                "config-resolver" to "INV-T-3983",
                "audit-log" to "INV-T-3984",
            )

        /**
         * The modules still pending, each with the task that clears it. Remove a module in the merge request
         * that leaves it with zero findings; never add one (INV-Q-4040). INV-T-3999 requires this to be empty.
         */
        val PENDING: Map<String, String> = emptyMap()

        /** The rules platform-build 0.10.0 added (INV-T-4135): RawSqlPlacement's and MigrationLint's tenant column. */
        private val RULES_0_10 = setOf("jdbc", "connection-provider", "tenancy-probe-in-main", "tenant-column")

        /** Owner ruling INV-Q-4196: the tasks that convert the library JDBC to jOOQ. */
        private val LIBRARY_JDBC_TASKS = setOf("INV-T-4202", "INV-T-4203")

        /** INV-T-4188: the 0.10.0 findings at adoption, by module and rule, each with the task that clears it. */
        private val PENDING_0_10_AT_ADOPTION: Map<Pair<String, String>, String> =
            mapOf(
                ("outbox-and-queue" to "tenant-column") to "INV-T-4134",
                ("service-runtime" to "tenant-column") to "INV-T-4134",
                ("config-resolver" to "tenant-column") to "INV-T-4134",
                ("rulepack-kernel" to "tenant-column") to "INV-T-4137",
            )

        /**
         * The 0.10.0 entries still pending. Remove an entry in the merge request that leaves it with zero findings;
         * never add one (INV-T-4188). INV-T-3999 requires this to be empty.
         */
        val PENDING_0_10: Map<Pair<String, String>, String> =
            mapOf(
                ("outbox-and-queue" to "tenant-column") to "INV-T-4134",
                ("config-resolver" to "tenant-column") to "INV-T-4134",
            )
    }
}
