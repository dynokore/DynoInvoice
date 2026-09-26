package com.dynokore.platform.adapterruntime.arch

import com.lemonappdev.konsist.api.Konsist
import gst.build.konsist.ArchitectureRules
import org.junit.jupiter.api.Test

/**
 * STK-41 architecture test: §18.9's L1 dependency order, on this module's own
 * sources. adapter-runtime uses no other L1 module (Keel INV-ADR-2774), so this
 * should hold trivially — the assertion is what makes that an enforced fact, not
 * just an intention in this module's README.
 *
 * Scans the whole module directory (`src/main`, `src/impl` and both test source
 * sets) rather than relying on Konsist's own source-set discovery for the
 * non-standard `impl`/`implTest` source sets this module's api/impl split adds.
 */
class ArchitectureTest {
    @Test
    fun `adapter-runtime depends on no other L1 module`() {
        ArchitectureRules.assertL1DependencyOrder(scope = Konsist.scopeFromDirectory("."))
    }
}
