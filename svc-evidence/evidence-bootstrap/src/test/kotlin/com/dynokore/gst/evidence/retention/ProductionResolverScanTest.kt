package com.dynokore.gst.evidence.retention

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * INV-Q-12588 A: no class under `src/main` of the four modules implements or creates an `EndUserPrincipalResolver` or
 * a `PolicyRoleResolver`; the access service and its authority replica fill them (INV-T-12601). The scan is
 * deny-by-default: any appearance of either name in the code of a main source, comments removed, is a finding.
 */
class ProductionResolverScanTest {
    private val root: Path = Path.of(System.getProperty("user.dir")).parent
    private val modules = listOf("evidence-domain", "evidence-application", "evidence-adapters", "evidence-bootstrap")
    private val ports = Regex("""\b(EndUserPrincipalResolver|PolicyRoleResolver)\b""")
    private val scanner = RetentionLiteralScanTest()

    private fun findings(files: List<Pair<String, String>>): List<String> =
        files.filter { (_, text) -> ports.containsMatchIn(scanner.code(text, keepStrings = false)) }.map { it.first }

    @Test
    fun `no main class implements or creates a principal or role resolver`() {
        val files =
            modules.flatMap { module ->
                val main = root.resolve(module).resolve("src/main")
                check(Files.isDirectory(main)) { "$main is missing" }
                Files.walk(main).use { stream ->
                    stream
                        .filter { it.toString().endsWith(".kt") }
                        .map { root.relativize(it).toString() to Files.readString(it) }
                        .toList()
                }
            }
        files.isNotEmpty() shouldBe true
        findings(files).shouldBeEmpty()
    }

    @Test
    fun `the scan finds an implementation, a lambda and an object expression`() {
        val cases =
            listOf(
                "class A : EndUserPrincipalResolver { }",
                "val r = PolicyRoleResolver { _, _, _, _, _ -> emptySet() }",
                "val o = object : EndUserPrincipalResolver { }",
            )
        cases.forEach { findings(listOf("x" to it)) shouldBe listOf("x") }
        findings(listOf("x" to "// EndUserPrincipalResolver\nclass B")) shouldBe emptyList()
    }
}
