package com.dynokore.platform.adapterruntime.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * STK-41 architecture test: adapter-runtime uses no other L1 module (Keel
 * INV-ADR-2774), on this module's own sources.
 *
 * Deliberately not `gst.build.konsist.ArchitectureRules.assertL1DependencyOrder()`:
 * that helper declares a Konsist `Layer` for every one of STK-60's eight L1 modules
 * (its own repository-wide canonical order) and requires each declared layer to have
 * at least one matching file in scope — a precondition this module's own directory
 * cannot satisfy for the other seven modules, most of which are still README-only
 * scaffolds. The requirement this module actually owns is narrower: its own imports
 * never name another L1 module's package, checked directly below.
 *
 * INV-T-2862 follow-up (review must-fix on INV-T-2866 / T22): this used to call
 * `Konsist.scopeFromDirectory(".")`. Konsist resolves a `scopeFromDirectory` path
 * against the auto-detected *project* root (found by walking up from the JVM's
 * working directory for a Gradle/Maven root marker), never against this module's
 * own directory — so `"."` always meant the repository root, on every module, no
 * matter which directory the `Test` task happens to run from. That silently scanned
 * every other module's sources too, including sibling L1 modules with legitimate
 * (non-adapter-runtime) imports of each other, and misattributed those imports to
 * this module. `Konsist.scopeFromModule` scopes correctly by module name instead,
 * explicitly across this module's four source sets (`main`, `impl`, `test` and
 * `implTest` — the non-standard split its api/impl layout adds, per its own
 * `build.gradle.kts`) so the fix keeps scanning the whole module, just only this
 * module.
 */
class ArchitectureTest {
    private val otherL1PackageRoots =
        listOf(
            "serviceruntime",
            "outboxandqueue",
            "rulepackkernel",
            "tenancykernel",
            "evidencestore",
            "configresolver",
            "auditlog",
        ).map { "com.dynokore.platform.$it" }

    private fun assertNoOtherL1Import(scope: KoScope) {
        scope.files.assertTrue { file ->
            file.imports.none { import -> otherL1PackageRoots.any { root -> import.name.startsWith(root) } }
        }
    }

    @Test
    fun `adapter-runtime imports no other L1 module`() {
        assertNoOtherL1Import(
            Konsist.scopeFromModule("adapter-runtime", "main", "impl", "test", "implTest"),
        )
    }

    /**
     * CS-50: proves the rule still has teeth after the scoping fix, against a
     * fixture rather than this repository's own sources (this module has no real
     * violation to exercise it with) — the same pattern
     * `service-runtime`'s `L1DependencyOrderTest` uses for its own Konsist rule.
     */
    @Test
    fun `would fail if adapter-runtime imported another L1 module`(
        @TempDir dir: File,
    ) {
        val offender = File(dir, "Offender.kt")
        offender.writeText(
            "package com.dynokore.platform.adapterruntime\n" +
                "import com.dynokore.platform.tenancykernel.Stub\n" +
                "class Offender(val s: Stub)\n",
        )

        assertThrows(Throwable::class.java) {
            assertNoOtherL1Import(Konsist.scopeFromExternalDirectory(dir.absolutePath))
        }
    }
}
