package com.dynokore.platform.serviceruntime.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import gst.build.konsist.MigrationLint
import gst.build.konsist.SqlGuards
import gst.build.konsist.sql.GuardViolation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * platform-build 0.9.0's SQL guards (INV-T-3985) over the production sources of every platform-libs module
 * (INV-REQ-854 b5, INV-ADR-3968 item 11; coordinator rulings INV-Q-4036, INV-Q-4040): `SqlGuards` (no raw jOOQ
 * SQL, identifiers only through tenancy-kernel's `SqlIdentifier.require`, session settings only in tenancy-kernel)
 * and `MigrationLint` (definer search_path and owner, immutable-table grants). Nothing is suppressed.
 *
 * Hosted here, like `L1DependencyOrderTest`, because service-runtime is the composition root; the scope is the
 * whole repository (`Konsist.scopeFromProduction()`), not this module.
 *
 * [PENDING] names the modules whose existing findings are cleared by the task that rewrites them (INV-Q-4040).
 * Their findings are listed by owner on every run, never waived; a finding in any other module fails the build.
 * The list may only shrink: a module may not be added (it must stay within [PENDING_AT_ADOPTION]), and a pending
 * module that has no finding left fails this test until it is removed from the list. INV-T-3999 requires it empty.
 */
class SqlGuardsTest {
    private val rootDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "tenancy-kernel").isDirectory }

    private val findings: Map<String, List<GuardViolation>> by lazy {
        guardFindings(Konsist.scopeFromProduction(), rootDir).groupBy { moduleOf(it) }
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
    fun `the scope covers the production sources of every module`() {
        val scanned = Konsist.scopeFromProduction().files.map { moduleOf(it.path) }.toSet()
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

    /** Every module directory with Kotlin sources in a non-test source set. */
    private fun productionModules(): List<String> = rootDir.listFiles().orEmpty()
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
    ): List<GuardViolation> = SqlGuards.findRawJooqSql(scope) +
        SqlGuards.findInterpolatedSql(scope) +
        SqlGuards.findSessionSettings(scope) +
        MigrationLint.findViolations(projectDir, scope)

    private fun moduleOf(violation: GuardViolation): String = moduleOf(violation.location)

    private fun moduleOf(path: String): String {
        val file = File(path).let { if (it.isAbsolute) it else File(rootDir, path) }
        return file.absoluteFile.relativeToOrNull(rootDir)?.invariantSeparatorsPath?.substringBefore('/')
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
    }
}
