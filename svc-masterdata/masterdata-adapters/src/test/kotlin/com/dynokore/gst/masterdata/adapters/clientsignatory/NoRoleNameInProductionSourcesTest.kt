package com.dynokore.gst.masterdata.adapters.clientsignatory

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RolePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * INV-Q-10027, INV-Q-17446 A: a service checks a scoped permission key, never a role name. This gate reads every role
 * key of the pinned catalogue at test time (`RolePermissions.roles(form)` for SAAS and PRIVATE; no count is written)
 * and fails when a production source file of `masterdata-application/` or `masterdata-adapters/` holds one as a string
 * literal. It scans git-tracked-style source only: `src/main` of the two modules, never `build`, `.gradle` or a cache.
 */
class NoRoleNameInProductionSourcesTest {
    private val forms = listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)

    private fun roleKeys(): Set<String> = forms.flatMap { RolePermissions.roles(it) }.toSet()

    private val skipped = setOf("build", ".gradle", ".gradle-cache", ".git")

    /** A `"role"` literal, plain or inside a raw string, found in [text]; the role must be the whole literal. */
    internal fun roleLiterals(
        text: String,
        roles: Set<String>,
    ): Set<String> = roles.filter { text.contains("\"$it\"") }.toSet()

    internal fun sourceFiles(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { file -> root.relativize(file).none { it.toString() in skipped } }
                .filter { it.fileName.toString().let { n -> n.endsWith(".kt") || n.endsWith(".java") } }
                .toList()
        }

    internal fun findings(
        mainRoots: List<Path>,
        roles: Set<String>,
    ): List<String> =
        mainRoots.filter { Files.isDirectory(it) }.flatMap { root ->
            sourceFiles(root).flatMap { file ->
                roleLiterals(Files.readString(file), roles).map { "$file holds the role key \"$it\"" }
            }
        }

    private fun productionRoots(): List<Path> {
        val adapters = Path.of("").toAbsolutePath()
        return listOf(
            adapters.resolve("src/main"),
            adapters.resolveSibling("masterdata-application").resolve("src/main"),
        )
    }

    @Test
    fun `INV-AC-17444 the catalogue lists roles for both forms and no production source holds one as a literal`() {
        val roles = roleKeys()
        assertTrue(roles.isNotEmpty())
        assertTrue(forms.all { RolePermissions.roles(it).isNotEmpty() })
        val roots = productionRoots()
        assertTrue(roots.all { Files.isDirectory(it) }, "both production roots exist: $roots")
        assertEquals(emptyList<String>(), findings(roots, roles))
    }

    @Test
    fun `INV-AC-17444 probe a role key in a production file fails, in a test file passes, a look-alike passes`(
        @TempDir dir: Path,
    ) {
        val roles = roleKeys()
        assertTrue("agent.operator" in roles)
        val production = Files.createDirectories(dir.resolve("m/src/main/kotlin"))
        val tests = Files.createDirectories(dir.resolve("m/src/test/kotlin"))
        Files.writeString(production.resolve("A.kt"), "val a = \"agent.operatorx\"\n")
        Files.writeString(production.resolve("B.kt"), "val b = \"agent\"\nval c = \"agent.operator.\"\n")
        Files.writeString(tests.resolve("T.kt"), "val t = \"agent.operator\"\n")
        assertEquals(emptyList<String>(), findings(listOf(dir.resolve("m/src/main")), roles))
        Files.writeString(production.resolve("C.kt"), "val c = \"agent.operator\"\n")
        val found = findings(listOf(dir.resolve("m/src/main")), roles)
        assertEquals(1, found.size)
        assertTrue(found.single().contains("C.kt"))
        assertFalse(found.any { it.contains("T.kt") })
    }

    @Test
    fun `INV-AC-17444 a role key is found inside a raw string and next to other code on one line`(
        @TempDir dir: Path,
    ) {
        val roles = roleKeys()
        val production = Files.createDirectories(dir.resolve("m/src/main"))
        Files.writeString(production.resolve("D.kt"), "val d = \"\"\"x \"agent.operator\" y\"\"\"; val e = 1\n")
        assertEquals(1, findings(listOf(production), roles).size)
    }
}
