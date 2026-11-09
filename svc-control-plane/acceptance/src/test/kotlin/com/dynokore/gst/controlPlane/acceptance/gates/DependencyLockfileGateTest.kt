package com.dynokore.gst.controlPlane.acceptance.gates

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** INV-T-19011: no lockfile of the repository may lock the group aopalliance (licence class linked, STK-66). */
class DependencyLockfileGateTest {
    private val skipped = setOf("build", ".gradle", ".git", ".gst-licence-inputs")
    private val forbiddenGroup = "aopalliance"

    private fun readRoot(value: String?): File {
        require(!value.isNullOrBlank()) { "gst.repo.root is unset or blank" }
        val root = File(value)
        require(root.isDirectory) { "gst.repo.root is not an existing directory: $value" }
        return root
    }

    private fun lockfiles(root: File): List<String> {
        val found = mutableListOf<String>()

        fun walk(dir: File) {
            for (child in (dir.listFiles() ?: error("cannot list $dir")).sortedBy { it.name }) {
                if (child.isDirectory) {
                    if (child.name !in skipped) walk(child)
                } else if (child.name.endsWith(".lockfile")) {
                    found += child.relativeTo(root).invariantSeparatorsPath
                }
            }
        }
        walk(root)
        check(found.isNotEmpty()) { "no *.lockfile file under $root" }
        return found
    }

    private fun findings(root: File): List<String> =
        lockfiles(root).filter { rel ->
            File(root, rel).readLines().any { line ->
                line.isNotBlank() && !line.startsWith("#") && line.substringBefore(':') == forbiddenGroup
            }
        }

    private fun tree(vararg files: Pair<String, String>): File {
        val root = Files.createTempDirectory("lockgate").toFile()
        files.forEach { (rel, text) ->
            val f = File(root, rel)
            f.parentFile.mkdirs()
            f.writeText(text)
        }
        return root
    }

    private val bad = "aopalliance:aopalliance:1.0=runtimeClasspath\n"

    @Test
    fun `INV-AC-19006 no lockfile of the repository names the group aopalliance`() {
        findings(readRoot(System.getProperty("gst.repo.root"))).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19006 the scan reads the module lockfiles`() {
        val scanned = lockfiles(readRoot(System.getProperty("gst.repo.root")))
        listOf(
            "control-plane-adapters/gradle.lockfile",
            "control-plane-application/gradle.lockfile",
            "control-plane-bootstrap/gradle.lockfile",
            "control-plane-contract/gradle.lockfile",
            "control-plane-domain/gradle.lockfile",
            "acceptance/gradle.lockfile",
        ).forEach { scanned shouldContain it }
    }

    @Test
    fun `INV-AC-19006 a lockfile line with the group aopalliance is a finding`() {
        val root =
            tree(
                "a/gradle.lockfile" to bad,
                "b/buildscript-gradle.lockfile" to bad,
                "c/settings-gradle.lockfile" to bad,
            )
        findings(root) shouldContainExactlyInAnyOrder
            listOf("a/gradle.lockfile", "b/buildscript-gradle.lockfile", "c/settings-gradle.lockfile")
    }

    @Test
    fun `INV-AC-19006 a similar group or artifact name or a comment is not a finding`() {
        val text =
            "org.glassfish.hk2.external:aopalliance-repackaged:3.0.6=runtimeClasspath\n" +
                "aopalliance-x:aopalliance:1.0=runtimeClasspath\n" +
                "org.aopalliance:aopalliance:1.0=runtimeClasspath\n" +
                "# aopalliance:aopalliance:1.0\n\n"
        findings(tree("m/gradle.lockfile" to text)).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19006 a lockfile under build is not scanned`() {
        findings(tree("m/build/gradle.lockfile" to bad, "m/gradle.lockfile" to "a:b:1=c\n")).shouldBeEmpty()
        findings(tree("m/builds/gradle.lockfile" to bad)) shouldContainExactlyInAnyOrder
            listOf("m/builds/gradle.lockfile")
    }

    @Test
    fun `INV-AC-19006 a tree with no lockfile is an error`() {
        shouldThrow<IllegalStateException> { findings(Files.createTempDirectory("lockgate-empty").toFile()) }
    }

    @Test
    fun `INV-AC-19006 an unset or missing repository root is an error`() {
        shouldThrow<IllegalArgumentException> { readRoot(null) }
        shouldThrow<IllegalArgumentException> { readRoot("") }
        shouldThrow<IllegalArgumentException> { readRoot("  ") }
        shouldThrow<IllegalArgumentException> { readRoot("/nonexistent/gst-root-19011") }
    }
}
