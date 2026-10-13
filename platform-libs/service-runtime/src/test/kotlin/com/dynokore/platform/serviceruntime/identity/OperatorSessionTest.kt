package com.dynokore.platform.serviceruntime.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The caseId rule (coordinator rulings INV-Q-4529, INV-Q-4534) is held by [OperatorSession] itself, so no resolver
 * (the default one, a service's own) and no direct construction can carry an invalid case into an audit reason.
 */
class OperatorSessionTest {
    private val whitespace =
        listOf(0x20, 0xA0, 0x1680) + (0x2000..0x200A) + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)
    private val controls = listOf("\u0000", "\u001B", "\u001F", "\n", "\r", "\t", "\u007F", "\u0085", "\u009F")

    private fun text(codePoint: Int) = String(Character.toChars(codePoint))

    @Test
    fun `a valid case and role construct`() {
        val longest = listOf("C", "\uD83D\uDE00").map { it.repeat(64) }
        val valid = listOf("CASE-5521", " CASE-5521 ", "CASE 5521/a-b", "\uFEFFA") + longest
        for (case in valid) {
            OperatorSession("platform.support", case).case shouldBe case
        }
    }

    @Test
    fun `a direct construction with each bad case class throws`() {
        val bad =
            mapOf(
                "empty" to listOf(""),
                "too long" to listOf("C".repeat(65), "\uD83D\uDE00".repeat(65)),
                "colon" to listOf(":", ": ", "A:B", "CASE-1: approved", "CASE-1:"),
                "control" to controls.map { "A${it}B" },
                "whitespace only" to whitespace.map(::text) + whitespace.joinToString("", transform = ::text),
            )
        for ((kind, cases) in bad) {
            for (case in cases) {
                withClue(kind) {
                    shouldThrow<IllegalArgumentException> { OperatorSession("platform.support", case) }
                    OperatorSession.isValidCase(case) shouldBe false
                }
            }
        }
    }

    @Test
    fun `a blank role or one holding a control character throws`() {
        for (role in listOf("", " ", "platform.support\n", "platform.support\u0085")) {
            shouldThrow<IllegalArgumentException> { OperatorSession(role, "CASE-5521") }
        }
    }
}
