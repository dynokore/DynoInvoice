package com.dynokore.gst.consoleBff.acceptance

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.core.exception.KoAssertionFailedException
import gst.build.konsist.ArchitectureRules
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * TPL-14 architecture test (ARC-03, owner answer INV-Q-5331 (D)): the layer rule over the real production sources,
 * and one probe per import the rule must refuse or accept in `console-bff-domain`.
 */
class ArchitectureTest {
    private val root = "com.dynokore.gst.consoleBff"
    private val modules = listOf("domain", "application", "adapters", "bootstrap", "contract")

    @Test
    fun `INV-AC-14437 the real production sources keep the layer rules`() {
        // the contract module's sources are generated, outside src/main
        val dirs =
            modules.map { "console-bff-$it/src/main" }.filter { it != "console-bff-contract/src/main" } +
                "console-bff-contract/build/generated/contract-server/src/main/kotlin"
        // A layer without a main source directory yet (console-bff-application) has nothing to violate, but Konsist
        // refuses an empty layer: a placeholder file under acceptance/build stands in for it, never in production.
        val rootDir = File(System.getProperty("gst.root.dir"))
        val missing = modules.filter { it != "contract" && !File(rootDir, "console-bff-$it/src/main").isDirectory }
        val placeholders = "acceptance/build/placeholders"
        missing.forEach { layer ->
            File("build/placeholders/$layer/Placeholder.kt")
                .also { it.parentFile.mkdirs() }
                .writeText("package $root.$layer\n\nclass Placeholder\n")
        }
        val existing = dirs.filter { File(rootDir, it).isDirectory }
        ArchitectureRules.assertServiceModuleDirection(
            root,
            Konsist.scopeFromDirectories(existing + missing.map { "$placeholders/$it" }),
        )
    }

    // Konsist resolves a directory against the Gradle root project, so a probe lives under acceptance/build.
    private fun assertDomainRule(
        importLine: String,
        refused: Boolean,
    ) {
        val relative = "acceptance/build/probes/${UUID.randomUUID()}"
        val file = File("build/probes/${relative.substringAfterLast('/')}/domain/Probe.kt")
        file.parentFile.mkdirs()
        file.writeText("package $root.domain\n\n$importLine\n\nclass Probe\n")
        // every layer of the rule needs at least one file
        modules.filter { it != "domain" }.forEach { layer ->
            File(file.parentFile.parentFile, "$layer/Probe.kt")
                .also { it.parentFile.mkdirs() }
                .writeText("package $root.$layer\n\nclass Probe\n")
        }
        try {
            val run = { ArchitectureRules.assertServiceModuleDirection(root, Konsist.scopeFromDirectory(relative)) }
            if (refused) assertThrows(KoAssertionFailedException::class.java, run) else assertDoesNotThrow(run)
        } finally {
            file.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `INV-AC-14433 a jOOQ import in the domain fails the rule`() =
        assertDomainRule("import org.jooq.DSLContext", refused = true)

    @Test
    fun `INV-AC-14433 a Spring import in the domain fails the rule`() =
        assertDomainRule("import org.springframework.stereotype.Component", refused = true)

    @Test
    fun `INV-AC-14433 a java sql Timestamp import in the domain passes the rule`() =
        assertDomainRule("import java.sql.Timestamp", refused = false)

    @Test
    fun `INV-AC-14433 a java sql SQLException import in the domain passes the rule`() =
        assertDomainRule("import java.sql.SQLException", refused = false)

    @Test
    fun `INV-AC-14433 a javax sql import in the domain passes the rule`() =
        assertDomainRule("import javax.sql.DataSource", refused = false)
}
