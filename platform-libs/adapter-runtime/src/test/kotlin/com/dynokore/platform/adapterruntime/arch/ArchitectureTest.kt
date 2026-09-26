package com.dynokore.platform.adapterruntime.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

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
 * Scans the whole module directory (`src/main`, `src/impl` and both test source
 * sets) rather than relying on Konsist's own source-set discovery for the
 * non-standard `impl`/`implTest` source sets this module's api/impl split adds.
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

    @Test
    fun `adapter-runtime imports no other L1 module`() {
        Konsist.scopeFromDirectory(".").files.assertTrue { file ->
            file.imports.none { import -> otherL1PackageRoots.any { root -> import.name.startsWith(root) } }
        }
    }
}
