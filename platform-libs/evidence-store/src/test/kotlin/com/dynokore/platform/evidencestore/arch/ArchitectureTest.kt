package com.dynokore.platform.evidencestore.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * ARC-16: the api half (`src/main`) of evidence-store is framework-free. Deny by default: an import is allowed only
 * when it starts with a listed prefix (each ends in a dot) or equals a listed name. The impl half is not checked.
 */
class ArchitectureTest {
    private val allowedPrefixes =
        listOf(
            "com.dynokore.platform.evidencestore.",
            "com.dynokore.platform.crypto.",
            "com.dynokore.platform.tenancykernel.",
            "kotlin.",
            "java.time.",
            "java.util.",
            "java.security.",
            "java.nio.charset.",
        )

    private companion object {
        const val MAIN_PATH = "/evidence-store/src/main/"
    }

    private val allowedNames = setOf("java.nio.ByteBuffer")

    private fun allowed(name: String) = name in allowedNames || allowedPrefixes.any { name.startsWith(it) }

    private fun assertFrameworkFree(files: List<KoFileDeclaration>) {
        files.assertTrue { file -> file.imports.all { allowed(it.name) } }
    }

    private fun assertFrameworkFree(scope: KoScope) = assertFrameworkFree(scope.files)

    // scopeFromModule(.., "main") also returns the files of src/impl and src/test (observed with konsist 0.17.3), so
    // the main half is the module scope restricted to the path of src/main (the leading slash
    // keeps `old-evidence-store/src/main/` out).
    private fun mainFiles(substring: String = MAIN_PATH): List<KoFileDeclaration> =
        Konsist.scopeFromModule("evidence-store", "main").files.filter { canonical(it.path).contains(substring) }

    private fun canonical(path: String) = path.replace('\\', '/')

    private fun mainDirectory(): File =
        listOf(File("src/main"), File("evidence-store/src/main")).first { it.isDirectory }.canonicalFile

    private fun probe(
        dir: File,
        index: Int,
        import: String,
    ): KoScope {
        val only = File(dir, "only$index").also { it.mkdirs() }
        val source = "package com.dynokore.platform.evidencestore\nimport $import\nclass Probe$index\n"
        File(only, "Probe$index.kt").writeText(source)
        return Konsist.scopeFromExternalDirectory(only.absolutePath)
    }

    @Test
    fun `the main source of evidence-store imports only the allowed names`() {
        assertFrameworkFree(mainFiles())
    }

    @Test
    fun `probe - the rule fails on an import outside the allowed list`(
        @TempDir dir: File,
    ) {
        listOf(
            "java.sql.Connection",
            "javax.sql.DataSource",
            "org.jooq.DSLContext",
            "org.springframework.stereotype.Component",
            "software.amazon.awssdk.services.s3.S3Client",
            "java.nio.file.Files",
            "java.io.File",
            "java.utilx.Fake",
            "java.timex.Fake",
            "com.dynokore.platform.evidencestorex.Stub",
            "com.dynokore.platform.tenancyjooq.Stub",
        ).forEachIndexed { i, import ->
            assertThrows(Throwable::class.java, { assertFrameworkFree(probe(dir, i, import)) }, import)
        }
    }

    @Test
    fun `probe - the rule accepts the allowed imports`(
        @TempDir dir: File,
    ) {
        listOf(
            "java.time.Instant",
            "java.util.UUID",
            "java.security.MessageDigest",
            "java.nio.ByteBuffer",
            "java.nio.charset.StandardCharsets",
            "com.dynokore.platform.crypto.Hashing",
            "kotlin.collections.List",
        ).forEachIndexed { i, import ->
            assertDoesNotThrow({ assertFrameworkFree(probe(dir, i, import)) }, import)
        }
    }

    private fun assertScopeIsTheSourceSet(substring: String) {
        val onDisk =
            mainDirectory()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.canonicalPath }
                .toSet()
        val scoped = mainFiles(substring)
        assertTrue(scoped.isNotEmpty(), "the filtered scope is empty")
        assertEquals(onDisk, scoped.map { File(it.path).canonicalPath }.toSet())
        val others = listOf("/src/impl/", "/src/test/", "/src/testFixtures/")
        assertTrue(scoped.none { f -> others.any { canonical(f.path).contains(it) } })
    }

    @Test
    fun `non-vacuity - the filtered scope equals the kt files of src main found on disk`() {
        assertScopeIsTheSourceSet(MAIN_PATH)
    }

    @Test
    fun `non-vacuity - a filter that matches nothing or too much fails the guard`() {
        assertThrows(Throwable::class.java) { assertScopeIsTheSourceSet("/evidence-store/src/mainx/") }
        assertThrows(Throwable::class.java) { assertScopeIsTheSourceSet("/evidence-store/src/") }
    }

    @Test
    fun `the impl half is not checked`() {
        val impl = Konsist.scopeFromModule("evidence-store", "impl")
        assertTrue(impl.files.any { f -> f.imports.any { it.name.startsWith("org.jooq.") } })
        assertFalse(impl.files.all { f -> f.imports.all { allowed(it.name) } })
    }
}
