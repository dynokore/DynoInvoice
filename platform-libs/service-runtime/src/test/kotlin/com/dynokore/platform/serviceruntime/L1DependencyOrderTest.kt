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
    fun `passes a fixture that uses only what Keel allows`(
        @TempDir dir: File,
    ) {
        writeL1ModuleStubs(dir)
        // INV-MOD "Order within L1": audit-log uses outbox-and-queue, which uses
        // tenancy-kernel; the rule enforces the transitive closure, so audit-log may
        // import tenancy-kernel too. service-runtime is the composition root.
        write(dir, "auditlog/A.kt", importing("auditlog", "outboxandqueue", "A"))
        write(dir, "auditlog/B.kt", importing("auditlog", "tenancykernel", "B"))
        write(dir, "configresolver/C.kt", importing("configresolver", "auditlog", "C"))
        write(dir, "evidencestore/E.kt", importing("evidencestore", "tenancykernel", "E"))
        write(dir, "rulepackkernel/R.kt", importing("rulepackkernel", "tenancykernel", "R"))
        write(dir, "serviceruntime/S.kt", importing("serviceruntime", "adapterruntime", "S"))
        write(dir, "serviceruntime/T.kt", importing("serviceruntime", "configresolver", "T"))

        assertDoesNotThrow { assertOrder(dir) }
    }

    @Test
    fun `fails when a module that uses nothing imports another`(
        @TempDir dir: File,
    ) {
        writeL1ModuleStubs(dir)
        write(dir, "tenancykernel/T.kt", importing("tenancykernel", "auditlog", "T"))

        assertThrows(Throwable::class.java) { assertOrder(dir) }
    }

    @Test
    fun `fails when a module imports a sibling outside its allowed uses`(
        @TempDir dir: File,
    ) {
        writeL1ModuleStubs(dir)
        // evidence-store uses only tenancy-kernel, even though a linear order would allow this.
        write(dir, "evidencestore/E.kt", importing("evidencestore", "outboxandqueue", "E"))

        assertThrows(Throwable::class.java) { assertOrder(dir) }
    }

    @Test
    fun `fails when another L1 module depends on service-runtime`(
        @TempDir dir: File,
    ) {
        writeL1ModuleStubs(dir)
        write(dir, "auditlog/A.kt", importing("auditlog", "serviceruntime", "A"))

        assertThrows(Throwable::class.java) { assertOrder(dir) }
    }

    private fun assertOrder(dir: File) {
        ArchitectureRules.assertL1DependencyOrder(scope = Konsist.scopeFromExternalDirectory(dir.absolutePath))
    }

    private fun importing(
        from: String,
        to: String,
        name: String,
    ) = "package com.dynokore.platform.$from\n" +
        "import com.dynokore.platform.$to.Stub\n" +
        "class $name(val s: Stub)\n"

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
