package com.dynokore.gst.rules.bootstrap

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * INV-AC-19007: no `*.lockfile` of the repository locks the group `aopalliance` (STK-66 linked licence class; the
 * redundant module is excluded in the platform-libs service-runtime POM). Deny-by-default scan: every file whose name
 * ends with `.lockfile` is read, except below the four directories named exactly in [SKIPPED_DIRECTORIES].
 */
class DependencyLockfileTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `INV-AC-19007 no lockfile of the repository names the group aopalliance`() {
        val root = repositoryRoot(System.getProperty(ROOT_PROPERTY))
        findings(root).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19007 the scan reads the module lockfiles`() {
        val root = repositoryRoot(System.getProperty(ROOT_PROPERTY))
        val scanned = lockfiles(root).map { relative(root, it) }.toSet()
        listOf("adapters", "application", "bootstrap", "contract", "domain").forEach {
            scanned shouldContain "rules-$it/gradle.lockfile"
        }
    }

    @Test
    fun `INV-AC-19007 a lockfile line with the group aopalliance is a finding`() {
        val paths = listOf("a/gradle.lockfile", "b/buildscript-gradle.lockfile", "c/settings-gradle.lockfile")
        paths.forEach { write(it, "aopalliance:aopalliance:1.0=runtimeClasspath") }
        findings(tmp).toSet() shouldBe paths.toSet()
    }

    @Test
    fun `INV-AC-19007 a similar group or artifact name or a comment is not a finding`() {
        write(
            "m/gradle.lockfile",
            """
            # This is a Gradle generated file for dependency locking.

            org.glassfish.hk2.external:aopalliance-repackaged:3.0.6=runtimeClasspath
            aopalliance-x:aopalliance:1.0=runtimeClasspath
            org.aopalliance:aopalliance:1.0=runtimeClasspath
            # aopalliance:aopalliance:1.0
            """.trimIndent(),
        )
        findings(tmp).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19007 a lockfile under build is not scanned`() {
        write("m/build/gradle.lockfile", "aopalliance:aopalliance:1.0=runtimeClasspath")
        write("m/.gradle/gradle.lockfile", "aopalliance:aopalliance:1.0=runtimeClasspath")
        write("m/.git/gradle.lockfile", "aopalliance:aopalliance:1.0=runtimeClasspath")
        write("m/.gst-licence-inputs/gradle.lockfile", "aopalliance:aopalliance:1.0=runtimeClasspath")
        write("m/ok.lockfile", "org.example:lib:1=runtimeClasspath")
        findings(tmp).shouldBeEmpty()
        write("m/builds/gradle.lockfile", "aopalliance:aopalliance:1.0=runtimeClasspath")
        findings(tmp) shouldBe listOf("m/builds/gradle.lockfile")
    }

    @Test
    fun `INV-AC-19007 a tree with no lockfile is an error`() {
        assertThrows<IllegalStateException> { findings(tmp) }
        write("m/build/gradle.lockfile", "x:y:1=z") // only a skipped one: still no lockfile scanned
        assertThrows<IllegalStateException> { findings(tmp) }
    }

    @Test
    fun `INV-AC-19007 an unset or missing repository root is an error`() {
        assertThrows<IllegalStateException> { repositoryRoot(null) }
        assertThrows<IllegalStateException> { repositoryRoot("") }
        assertThrows<IllegalStateException> { repositoryRoot("   ") }
        assertThrows<IllegalStateException> { repositoryRoot(tmp.resolve("does-not-exist").toString()) }
        repositoryRoot(tmp.toString()) shouldBe tmp
    }

    private fun write(
        rel: String,
        text: String,
    ) {
        val file = tmp.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private companion object {
        const val ROOT_PROPERTY = "gst.repo.root"
        const val FORBIDDEN_GROUP = "aopalliance"
        val SKIPPED_DIRECTORIES = setOf("build", ".gradle", ".git", ".gst-licence-inputs")

        fun repositoryRoot(value: String?): Path {
            check(!value.isNullOrBlank()) { "system property $ROOT_PROPERTY is unset or blank" }
            val path = Path.of(value)
            check(Files.isDirectory(path)) { "repository root $value is not a directory" }
            return path
        }

        fun relative(
            root: Path,
            file: Path,
        ): String = root.relativize(file).joinToString("/")

        fun lockfiles(root: Path): List<Path> {
            val found = mutableListOf<Path>()
            Files.walkFileTree(
                root,
                object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult =
                        if (dir != root && dir.fileName.toString() in SKIPPED_DIRECTORIES) {
                            java.nio.file.FileVisitResult.SKIP_SUBTREE
                        } else {
                            java.nio.file.FileVisitResult.CONTINUE
                        }

                    override fun visitFile(
                        file: Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (attrs.isRegularFile && file.fileName.toString().endsWith(".lockfile")) found.add(file)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
            return found.sorted()
        }

        /** Relative paths of the lockfiles that hold a line whose group is exactly [FORBIDDEN_GROUP]. */
        fun findings(root: Path): List<String> {
            val files = lockfiles(root)
            check(files.isNotEmpty()) { "no *.lockfile found under $root: the scan would prove nothing" }
            return files
                .filter { file ->
                    Files.readAllLines(file).any { line ->
                        line.isNotBlank() && !line.startsWith("#") && line.substringBefore(':') == FORBIDDEN_GROUP
                    }
                }.map { relative(root, it) }
        }
    }
}
