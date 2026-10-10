package com.dynokore.platform.serviceruntime.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * STK-41 architecture test, on this module's own sources. service-runtime is the
 * L1 composition root (INV-MOD-2371): it may use the other L1 modules its service
 * embeds, and none may use it. T29 (INV-T-2873) uses exactly one, tenancy-kernel's
 * `TenantId` (the replicas are tenant-keyed), so that is the one allowed import
 * here; any other L1 module still fails until a task needs it and names it.
 * (This replaces the provisional "uses no other L1 module" reading, which
 * konsist-rules 0.2.0's inverted order encoded; platform-build 0.5.0 corrects it.)
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
    fun `service-runtime imports no L1 module beyond tenancy-kernel`() {
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
    fun `would fail if service-runtime imported an L1 module beyond tenancy-kernel`(
        @TempDir dir: File,
    ) {
        val offender = File(dir, "Offender.kt")
        offender.writeText(
            "package com.dynokore.platform.serviceruntime\n" +
                "import com.dynokore.platform.evidencestore.Stub\n" +
                "class Offender(val s: Stub)\n",
        )

        assertThrows(Throwable::class.java) {
            assertNoOtherL1Import(Konsist.scopeFromExternalDirectory(dir.absolutePath))
        }
    }
}
