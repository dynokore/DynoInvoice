package com.dynokore.gst.notification.acceptance

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
 * and one probe per import the rule must refuse or accept in `notification-domain`.
 */
class ArchitectureTest {
    private val root = "com.dynokore.gst.notification"
    private val modules = listOf("domain", "application", "adapters", "bootstrap", "contract")

    @Test
    fun `the real production sources keep the layer rules`() {
        // the contract module's sources are generated, outside src/main
        val dirs =
            modules.map { "notification-$it/src/main" }.filter { it != "notification-contract/src/main" } +
                "notification-contract/build/generated/contract-server/src/main/kotlin"
        ArchitectureRules.assertServiceModuleDirection(root, Konsist.scopeFromDirectories(dirs))
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
    fun `a jOOQ import in the domain fails the rule`() = assertDomainRule("import org.jooq.DSLContext", refused = true)

    @Test
    fun `a Spring import in the domain fails the rule`() =
        assertDomainRule("import org.springframework.stereotype.Component", refused = true)

    @Test
    fun `a java sql Timestamp import in the domain passes the rule`() =
        assertDomainRule("import java.sql.Timestamp", refused = false)

    @Test
    fun `a java sql SQLException import in the domain passes the rule`() =
        assertDomainRule("import java.sql.SQLException", refused = false)

    @Test
    fun `a javax sql import in the domain passes the rule`() =
        assertDomainRule("import javax.sql.DataSource", refused = false)
}
