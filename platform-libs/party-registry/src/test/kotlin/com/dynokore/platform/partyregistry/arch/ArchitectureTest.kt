package com.dynokore.platform.partyregistry.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Layer rule of party-registry (INV-MOD-2740, STK-60): the model is pure, so the rule is deny-by-default. A file of
 * `main` or `impl` may import only the module's own package, `kotlin.` and `java.time.`; anything else — Spring, jOOQ,
 * java.sql, java.io, another platform module, a service — is a finding. The allowlist entries are exact prefixes:
 * `kotlin.` (language library) and `java.time.` (the capture time type). Test sources are skipped.
 */
class ArchitectureTest {
    private val ownPackage = "com.dynokore.platform.partyregistry"
    private val allowedPrefixes = listOf("$ownPackage.", "kotlin.", "java.time.")

    private fun assertPure(scope: KoScope) {
        scope.files.filter { "/src/test/" !in it.path }.assertTrue { file ->
            file.imports.all { i -> allowedPrefixes.any { i.name.startsWith(it) } }
        }
    }

    @Test
    fun `party-registry model and impl import nothing outside the allowlist`() {
        assertPure(Konsist.scopeFromModule("party-registry", "main", "impl"))
    }

    @Test
    fun `probe - the rule fails on a Spring, jOOQ, I O, look-alike or platform import`(
        @TempDir dir: File,
    ) {
        listOf(
            "org.springframework.stereotype.Component",
            "org.jooq.DSLContext",
            "java.sql.Connection",
            "java.io.File",
            "java.nio.file.Files",
            "java.timex.Fake",
            "kotlinx.coroutines.Job",
            "com.dynokore.platform.partyregistryx.Stub",
            "com.dynokore.platform.tenancykernel.Stub",
        ).forEachIndexed { i, import ->
            val only = File(dir, "only$i").also { it.mkdirs() }
            File(only, "Offender$i.kt").writeText(
                "package com.dynokore.platform.partyregistry\nimport $import\nclass Offender$i\n",
            )
            assertThrows(
                Throwable::class.java,
                { assertPure(Konsist.scopeFromExternalDirectory(only.absolutePath)) },
                import,
            )
        }
    }
}
