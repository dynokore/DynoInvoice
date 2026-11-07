package com.dynokore.gst.evidence.retention

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * INV-T-9236, INV-Q-10107 C ("no code compares a role name directly"): the production code of the four modules
 * holds neither `PolicyRoleResolver`, nor `rolesOf`, nor the string literal `"tenant.admin"`. Deny-by-default: any
 * appearance in the code of a main source (comments removed, string literals kept for the literal) is a finding.
 */
class RoleFreeDecisionScanTest {
    private val root: Path = Path.of(System.getProperty("user.dir")).parent
    private val modules = listOf("evidence-domain", "evidence-application", "evidence-adapters", "evidence-bootstrap")
    private val denied = Regex("""\bPolicyRoleResolver\b|\brolesOf\b|tenant\.admin""")
    private val scanner = RetentionLiteralScanTest()

    private fun findings(files: List<Pair<String, String>>): List<String> =
        files.filter { (_, text) -> denied.containsMatchIn(scanner.code(text, keepStrings = true)) }.map { it.first }

    @Test
    fun `INV-AC-2224 no production code names a role resolver, rolesOf or the role tenant admin`() {
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
    fun `the scan finds a resolver bean, a rolesOf call and the literal and skips comments`() {
        val cases =
            listOf(
                "val r = PolicyRoleResolver { _, _, _, _, _ -> emptySet() }",
                "fun f(a: A) = a.rolesOf(t)",
                "val x = \"tenant.admin\"",
                "if (role == \"tenant.admin\") ok()",
            )
        cases.forEach { findings(listOf("x" to it)) shouldBe listOf("x") }
        findings(listOf("x" to "// PolicyRoleResolver rolesOf tenant.admin\nclass B")) shouldBe emptyList()
    }
}
