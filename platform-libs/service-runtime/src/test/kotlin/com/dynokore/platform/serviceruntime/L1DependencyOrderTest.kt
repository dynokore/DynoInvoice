package com.dynokore.platform.serviceruntime

import com.lemonappdev.konsist.api.Konsist
import gst.build.konsist.ArchitectureRules
import gst.build.konsist.L1_MODULE_ORDER
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Proves the §18.9 L1 dependency-order Konsist rule (STK-60, platform-build's
 * `konsist-rules`) is wired into this repository and genuinely fails an upward
 * dependency (done-when, INV-T-2860 / T16).
 *
 * Against a small fixture, not this repository's own sources: no Wave 1 module
 * has real code yet (every one is still the design-only scaffold this train
 * adds — repository README.md), so there is nothing of this repository's own
 * to scan. `ArchitectureRules.assertL1DependencyOrder`'s default scope
 * (`Konsist.scopeFromProject()`) starts checking the real modules the moment
 * they gain classes, with no change here — build-logic's own
 * `gst.library.gradle.kts` KDoc notes the same thing.
 */
class L1DependencyOrderTest {
    @Test
    fun `passes a fixture with a compliant order`(
        @TempDir dir: File,
    ) {
        writeL1ModuleStubs(dir)
        write(
            dir,
            "auditlog/A.kt",
            "package com.dynokore.platform.auditlog\n" +
                "import com.dynokore.platform.serviceruntime.Stub\nclass A(val s: Stub)\n",
        )

        assertDoesNotThrow {
            ArchitectureRules.assertL1DependencyOrder(scope = Konsist.scopeFromExternalDirectory(dir.absolutePath))
        }
    }

    @Test
    fun `fails a fixture with an upward dependency`(
        @TempDir dir: File,
    ) {
        writeL1ModuleStubs(dir)
        // service-runtime is first in L1_MODULE_ORDER; audit-log is last. A module
        // may depend only on one earlier in the order, never a later one.
        write(
            dir,
            "serviceruntime/S.kt",
            "package com.dynokore.platform.serviceruntime\n" +
                "import com.dynokore.platform.auditlog.Stub\nclass S(val a: Stub)\n",
        )

        assertThrows(Throwable::class.java) {
            ArchitectureRules.assertL1DependencyOrder(scope = Konsist.scopeFromExternalDirectory(dir.absolutePath))
        }
    }

    /** One stub file per STK-60 L1 module: every `Layer` the assertion references needs a file (Konsist). */
    private fun writeL1ModuleStubs(dir: File) {
        L1_MODULE_ORDER.forEach { module ->
            val segment = module.replace("-", "")
            write(dir, "$segment/Stub.kt", "package com.dynokore.platform.$segment\nclass Stub\n")
        }
    }

    private fun write(
        dir: File,
        relativePath: String,
        content: String,
    ) {
        val file = File(dir, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
