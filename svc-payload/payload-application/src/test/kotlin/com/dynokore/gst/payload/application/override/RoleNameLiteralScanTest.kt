package com.dynokore.gst.payload.application.override

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** No production source of the domain, application and adapters modules holds a role name as a string literal. */
class RoleNameLiteralScanTest {
    private val roles = setOf("tax.approver", "tenant.admin", "agent.operator", "entity.admin")
    private val token =
        Regex("/\\*|\\*/|//[^\\n]*|\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"")

    /** The literals equal to a role name; comments are not string literals and are not read. */
    fun offences(source: String): List<String> =
        literalsOutsideComments(source)
            .filter { it in roles }
            .toList()

    /** Walks comments and literals in order; block comments nest, so a role name inside one is never read. */
    private fun literalsOutsideComments(source: String): List<String> {
        val literals = mutableListOf<String>()
        var depth = 0
        for (piece in token.findAll(source).map { it.value }) {
            when {
                piece == "/*" -> depth++
                piece == "*/" -> if (depth > 0) depth--
                depth > 0 || piece.startsWith("//") -> Unit
                else -> literals += piece.trim('"')
            }
        }
        return literals
    }

    @Test
    fun `the production code of domain, application and adapters holds no role name literal`() {
        val repo = File("..").canonicalFile
        val found =
            listOf("payload-domain", "payload-application", "payload-adapters").flatMap { module ->
                File(repo, "$module/src/main")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file -> offences(file.readText()).map { "${file.relativeTo(repo)}: $it" } }
                    .toList()
            }
        found shouldBe emptyList()
    }

    @Test
    fun `a copy that adds a role name literal fails the scan`() {
        offences("val x = \"tax.approver\"").size shouldBe 1
        offences("val x = \"\"\"tenant.admin\"\"\"").size shouldBe 1
        offences("val x = \"entity.admin\" // agent.operator").size shouldBe 1
    }

    @Test
    fun `a role name in a comment or a longer literal is not a literal equal to a role name`() {
        offences("// tax.approver\n/* /* tenant.admin */ */ val x = \"validation.override\"") shouldBe emptyList()
        offences("val x = \"tax.approver2\"") shouldBe emptyList()
    }
}
