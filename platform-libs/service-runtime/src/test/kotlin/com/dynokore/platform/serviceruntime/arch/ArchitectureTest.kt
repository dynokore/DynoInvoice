package com.dynokore.platform.serviceruntime.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * STK-41 architecture test: service-runtime uses no other L1 module (§18.9;
 * this module is first in `L1_MODULE_ORDER`, so nothing is "before" it), on this
 * module's own sources.
 *
 * The same `Konsist.scopeFromModule` pattern adapter-runtime's own
 * `ArchitectureTest` fixed to (INV-T-2862 follow-up, review must-fix on
 * INV-T-2866 / T22): `Konsist.scopeFromDirectory(".")` resolves against the
 * auto-detected *project* root on every module, not this module's own
 * directory, so it would silently scan every sibling L1 module's sources too.
 * `Konsist.scopeFromModule` scopes correctly instead, explicitly across this
 * module's four source sets (`main`, `impl`, `test`, `implTest`).
 */
class ArchitectureTest {
    private val otherL1PackageRoots =
        listOf(
            "outboxandqueue",
            "rulepackkernel",
            "tenancykernel",
            "evidencestore",
            "adapterruntime",
            "configresolver",
            "auditlog",
        ).map { "com.dynokore.platform.$it" }

    private fun assertNoOtherL1Import(scope: KoScope) {
        scope.files.assertTrue { file ->
            file.imports.none { import -> otherL1PackageRoots.any { root -> import.name.startsWith(root) } }
        }
    }

    @Test
    fun `service-runtime imports no other L1 module`() {
        assertNoOtherL1Import(
            Konsist.scopeFromModule("service-runtime", "main", "impl", "test", "implTest"),
        )
    }

    /**
     * CS-50: proves the rule still has teeth against a fixture rather than this
     * repository's own sources (this module has no real violation to exercise it
     * with) — the same pattern `L1DependencyOrderTest` and adapter-runtime's own
     * `ArchitectureTest` use for theirs.
     */
    @Test
    fun `would fail if service-runtime imported another L1 module`(
        @TempDir dir: File,
    ) {
        val offender = File(dir, "Offender.kt")
        offender.writeText(
            "package com.dynokore.platform.serviceruntime\n" +
                "import com.dynokore.platform.tenancykernel.Stub\n" +
                "class Offender(val s: Stub)\n",
        )

        assertThrows(Throwable::class.java) {
            assertNoOtherL1Import(Konsist.scopeFromExternalDirectory(dir.absolutePath))
        }
    }
}
