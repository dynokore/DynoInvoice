package com.dynokore.gst.tenancy.acceptance.gates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** INV-T-19013: no lockfile of the repository may name the group aopalliance (licence gap, STK-66 linked class). */
class DependencyLockfileGateTest {
    private val skipped = setOf("build", ".gradle", ".git", ".gst-licence-inputs")

    private fun readRoot(value: String?): File {
        require(!value.isNullOrBlank()) { "gst.repo.root is unset or blank" }
        val dir = File(value)
        require(dir.isDirectory) { "gst.repo.root is not an existing directory: $value" }
        return dir
    }

    /** Every file whose name ends with .lockfile, as paths relative to the root; throws when there is none. */
    private fun scanned(root: File): List<String> {
        val found = mutableListOf<String>()

        fun walk(dir: File) {
            for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (child.isDirectory) {
                    if (child.name !in skipped) walk(child)
                } else if (child.name.endsWith(".lockfile")) {
                    found += child.relativeTo(root).invariantSeparatorsPath
                }
            }
        }
        walk(root)
        check(found.isNotEmpty()) { "no .lockfile file under ${root.path}" }
        return found
    }

    private fun findings(root: File): List<String> =
        scanned(root).filter { rel ->
            File(root, rel).readLines().any { line ->
                line.isNotBlank() && !line.startsWith("#") && line.substringBefore(':') == "aopalliance"
            }
        }

    private fun lockfile(
        root: File,
        path: String,
        vararg lines: String,
    ) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private val bad = "aopalliance:aopalliance:1.0=runtimeClasspath"

    @Test
    fun `INV-AC-19008 no lockfile of the repository names the group aopalliance`() {
        findings(readRoot(System.getProperty("gst.repo.root"))) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-19008 the scan reads the module lockfiles`() {
        val set = scanned(readRoot(System.getProperty("gst.repo.root"))).toSet()
        listOf(
            "tenancy-adapters/gradle.lockfile",
            "tenancy-application/gradle.lockfile",
            "tenancy-bootstrap/gradle.lockfile",
            "tenancy-contract/gradle.lockfile",
            "tenancy-domain/gradle.lockfile",
            "acceptance/gradle.lockfile",
        ).forEach { (it in set) shouldBe true }
    }

    @Test
    fun `INV-AC-19008 a lockfile line with the group aopalliance is a finding`(
        @TempDir tmp: File,
    ) {
        listOf("a/gradle.lockfile", "b/buildscript-gradle.lockfile", "c/settings-gradle.lockfile").forEach {
            lockfile(tmp, it, bad)
        }
        findings(tmp).sorted() shouldBe
            listOf("a/gradle.lockfile", "b/buildscript-gradle.lockfile", "c/settings-gradle.lockfile")
    }

    @Test
    fun `INV-AC-19008 a similar group or artifact name or a comment is not a finding`(
        @TempDir tmp: File,
    ) {
        lockfile(
            tmp,
            "m/gradle.lockfile",
            "org.glassfish.hk2.external:aopalliance-repackaged:3.0.6=runtimeClasspath",
            "aopalliance-x:aopalliance:1.0=runtimeClasspath",
            "org.aopalliance:aopalliance:1.0=runtimeClasspath",
            "# aopalliance:aopalliance:1.0",
            "",
        )
        findings(tmp) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-19008 a lockfile under build is not scanned`(
        @TempDir tmp: File,
    ) {
        lockfile(tmp, "ok/gradle.lockfile", "org.example:lib:1.0=runtimeClasspath")
        lockfile(tmp, "x/build/gradle.lockfile", bad)
        findings(tmp) shouldBe emptyList()
        lockfile(tmp, "x/builds/gradle.lockfile", bad)
        findings(tmp) shouldBe listOf("x/builds/gradle.lockfile")
    }

    @Test
    fun `INV-AC-19008 a tree with no lockfile is an error`(
        @TempDir tmp: File,
    ) {
        assertThrows(IllegalStateException::class.java) { findings(tmp) }
    }

    @Test
    fun `INV-AC-19008 an unset or missing repository root is an error`(
        @TempDir tmp: File,
    ) {
        listOf(null, "", "   ", File(tmp, "missing").path).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { readRoot(value) }
        }
    }
}
