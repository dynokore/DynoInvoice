package com.dynokore.platform.contracttests

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * INV-T-3989 (INV-REQ-1225): the BOM names the set of module versions this suite ran on (STK-60). For every shared-pool
 * module of the release, the BOM must carry an `api` and an `impl` constraint pair that reads the module's own version
 * (`project(":<module>").version`, so the version in the published POM is the one in the module's version.txt, never a
 * hand-edited number) and the module's CHANGELOG must have a released entry for that exact version.
 */
class BomTestedSetTest {
    private val root = File(System.getProperty("contracttests.repoRoot"))
    private val bom = File(root, "platform-libs-bom/build.gradle.kts").readText()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "outbox-and-queue", "tenancy-kernel", "service-runtime", "config-resolver", "audit-log", "tenancy-jooq",
            "rulepack-kernel",
        ],
    )
    fun `the BOM pins the api and impl pair of the module at its own version`(module: String) {
        for (artifact in listOf("$module-api", module)) {
            val pair = """api("com.dynokore.platform:$artifact:${'$'}{project(":$module").version}")"""
            bom.contains(pair) shouldBe true
        }
        val version = File(root, "$module/version.txt").readText().trim()
        val released = Regex("""^## \[${Regex.escape(version)}] - \d{4}-\d{2}-\d{2}$""", RegexOption.MULTILINE)
        released.containsMatchIn(File(root, "$module/CHANGELOG.md").readText()) shouldBe true
    }

    @Test
    fun `no BOM constraint carries a literal platform-libs version`() {
        val literal = Regex("""api\("com\.dynokore\.platform:[^:"]+:\d""")
        literal
            .findAll(bom)
            .map { it.value }
            .toList()
            .shouldBeEmpty()
    }
}
