package com.dynokore.platform.reconciliationengine.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Layer rule of reconciliation-engine (INV-MOD-2743, STK-60): an L2 module uses L1 modules only, and no module names a
 * service or a product event. This module needs no L1 module, so the rule is the stricter deny-by-default form: its
 * only imports from this platform are its own package.
 */
class ArchitectureTest {
    private val ownPackage = "com.dynokore.platform.reconciliationengine"

    private fun assertOnlyOwnPlatformImports(scope: KoScope) {
        scope.files.assertTrue { file ->
            file.imports.none { import ->
                (import.name.startsWith("com.dynokore.platform.") || import.name.startsWith("com.dynokore.gst.")) &&
                    !import.name.startsWith("$ownPackage.")
            }
        }
    }

    @Test
    fun `reconciliation-engine imports no other platform module, service or product event`() {
        assertOnlyOwnPlatformImports(Konsist.scopeFromModule("reconciliation-engine", "main", "impl", "test"))
    }

    @Test
    fun `probe - the rule fails on an import of a service or product-runtime type`(
        @TempDir dir: File,
    ) {
        listOf(
            "com.dynokore.platform.serviceruntime.Stub",
            "com.dynokore.platform.productruntime.Stub",
            "com.dynokore.gst.productRuntime.events.StructureChanged",
            "com.dynokore.platform.tenancykernel.Stub",
        ).forEachIndexed { i, import ->
            val only = File(dir, "only$i").also { it.mkdirs() }
            File(only, "Offender$i.kt").writeText(
                "package $ownPackage\nimport $import\nclass Offender$i\n",
            )
            assertThrows(
                Throwable::class.java,
                { assertOnlyOwnPlatformImports(Konsist.scopeFromExternalDirectory(only.absolutePath)) },
                import,
            )
        }
    }
}
