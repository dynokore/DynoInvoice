package com.dynokore.platform.serviceruntime.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import gst.build.konsist.L1_MODULE_ORDER
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * INV-T-2875 done-when 1 (INV-MOD-2371): service-runtime is the L1 composition root, so no other L1 module of
 * STK-60's order depends on it, neither in its sources (any source set, Konsist) nor in its build file.
 */
class CompositionRootDirectionTest {
    private val others = L1_MODULE_ORDER.filter { it != "service-runtime" }

    private fun assertNoServiceRuntimeImport(scope: KoScope) {
        scope.files.assertTrue { file ->
            file.imports.none { it.name.startsWith("com.dynokore.platform.serviceruntime") }
        }
    }

    @Test
    fun `no other L1 module imports service-runtime`() {
        others.forEach { module ->
            if (File("../$module/src").isDirectory) assertNoServiceRuntimeImport(Konsist.scopeFromModule(module))
        }
    }

    @Test
    fun `no other L1 module's build depends on service-runtime`() {
        others
            .map { File("../$it/build.gradle.kts") }
            .filter { it.isFile && it.readText().contains("project(\":service-runtime\")") }
            .map { it.path }
            .shouldBeEmpty()
    }

    /** CS-50: the import rule fails on a fixture module that uses service-runtime. */
    @Test
    fun `would fail if another L1 module imported service-runtime`(
        @TempDir dir: File,
    ) {
        File(dir, "Offender.kt").writeText(
            "package com.dynokore.platform.outboxandqueue\n" +
                "import com.dynokore.platform.serviceruntime.binding.EventBinding\n" +
                "class Offender(val b: EventBinding)\n",
        )
        assertThrows(Throwable::class.java) {
            assertNoServiceRuntimeImport(Konsist.scopeFromExternalDirectory(dir.absolutePath))
        }
    }
}
