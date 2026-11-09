package com.dynokore.gst.masterdata.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Lockfile guard (INV-T-19010, INV-AC-19005): no Gradle lockfile of the repository may name the group
 * `aopalliance` (STK-66 linked licence class; the module is redundant because spring-aop carries org.aopalliance).
 * The scan is deny-by-default: an empty tree or an unreadable root is an error, never an empty finding list.
 */
class DependencyLockfileTest {
    private val repository: File = repositoryRoot(File("..").absoluteFile.normalize().path)

    @Test
    fun `INV-AC-19005 no lockfile of the repository names the group aopalliance`() {
        val findings = findings(repository)
        assertTrue(findings.isEmpty(), "lockfiles naming the group aopalliance: $findings")
    }

    @Test
    fun `INV-AC-19005 the scan reads the module lockfiles`() {
        val scanned = lockfiles(repository).map { relative(repository, it) }.toSet()
        for (module in listOf("adapters", "application", "bootstrap", "contract", "domain")) {
            assertTrue("masterdata-$module/gradle.lockfile" in scanned, "not scanned: masterdata-$module; got $scanned")
        }
    }

    @Test
    fun `INV-AC-19005 a lockfile line with the group aopalliance is a finding`(
        @TempDir tmp: File,
    ) {
        val bad = "aopalliance:aopalliance:1.0=runtimeClasspath\n"
        val paths = listOf("a/gradle.lockfile", "b/buildscript-gradle.lockfile", "c/d/settings-gradle.lockfile")
        paths.forEach { write(tmp, it, bad) }
        assertEquals(paths.sorted(), findings(tmp))
    }

    @Test
    fun `INV-AC-19005 a similar group or artifact name or a comment is not a finding`(
        @TempDir tmp: File,
    ) {
        write(
            tmp,
            "m/gradle.lockfile",
            """
            # aopalliance:aopalliance:1.0
            org.glassfish.hk2.external:aopalliance-repackaged:3.0.6=runtimeClasspath
            aopalliance-x:aopalliance:1.0=runtimeClasspath
            org.aopalliance:aopalliance:1.0=runtimeClasspath

            empty=
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), findings(tmp))
    }

    @Test
    fun `INV-AC-19005 a lockfile under build is not scanned`(
        @TempDir tmp: File,
    ) {
        val bad = "aopalliance:aopalliance:1.0=runtimeClasspath\n"
        write(tmp, "ok/gradle.lockfile", "a:b:1=x\n")
        write(tmp, "m/build/gradle.lockfile", bad)
        write(tmp, "m/.gradle/gradle.lockfile", bad)
        write(tmp, "m/.git/gradle.lockfile", bad)
        write(tmp, "m/.gst-licence-inputs/gradle.lockfile", bad)
        assertEquals(emptyList<String>(), findings(tmp))
        write(tmp, "m/builds/gradle.lockfile", bad)
        assertEquals(listOf("m/builds/gradle.lockfile"), findings(tmp))
    }

    @Test
    fun `INV-AC-19005 a tree with no lockfile is an error`(
        @TempDir tmp: File,
    ) {
        assertThrows<IllegalStateException> { findings(tmp) }
    }

    @Test
    fun `INV-AC-19005 an unset or missing repository root is an error`(
        @TempDir tmp: File,
    ) {
        assertThrows<IllegalStateException> { repositoryRoot(null) }
        assertThrows<IllegalStateException> { repositoryRoot("") }
        assertThrows<IllegalStateException> { repositoryRoot("   ") }
        assertThrows<IllegalStateException> { repositoryRoot(File(tmp, "missing").path) }
    }

    private fun write(
        root: File,
        path: String,
        text: String,
    ) {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private companion object {
        val SKIPPED = setOf("build", ".gradle", ".git", ".gst-licence-inputs")

        fun repositoryRoot(value: String?): File {
            check(!value.isNullOrBlank()) { "repository root is unset or blank" }
            val root = File(value)
            check(root.isDirectory) { "repository root is not a directory: $value" }
            return root
        }

        fun lockfiles(root: File): List<File> {
            val found = mutableListOf<File>()

            fun walk(dir: File) {
                val children = dir.listFiles() ?: error("cannot list ${dir.path}")
                for (child in children.sortedBy { it.name }) {
                    if (child.isDirectory) {
                        if (child.name !in SKIPPED) walk(child)
                    } else if (child.name.endsWith(".lockfile")) {
                        found += child
                    }
                }
            }
            walk(root)
            check(found.isNotEmpty()) { "no lockfile under ${root.path}" }
            return found
        }

        fun relative(
            root: File,
            file: File,
        ): String = file.relativeTo(root).invariantSeparatorsPath

        /** Relative paths of the lockfiles that hold a line whose group is `aopalliance`. */
        fun findings(root: File): List<String> =
            lockfiles(root)
                .filter { file ->
                    file.readLines().any { line ->
                        line.isNotBlank() && !line.startsWith("#") && line.substringBefore(':') == "aopalliance"
                    }
                }.map { relative(root, it) }
                .sorted()
    }
}
