package com.dynokore.gst.submission.domain.listing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

class ListCursorTest {
    private val filter = "direction=PURCHASE"
    private val sort = "-dueDate"
    private val uuid36 = "123e4567-e89b-12d3-a456-426614174000"
    private val positions =
        listOf(
            CursorPosition("2026-03-20", uuid36, 1),
            CursorPosition("x", "d", 7),
            CursorPosition("a b,c", "doc-2", 2147483647),
        )

    private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun unb64(token: String) = String(Base64.getUrlDecoder().decode(token))

    private fun binding(
        f: String,
        s: String,
    ) = MessageDigest.getInstance("SHA-256").digest("$f\n$s".toByteArray()).joinToString("") {
        String.format(java.util.Locale.ROOT, "%02x", it)
    }

    private fun malformed() = CursorDecoding.Refused(CursorRefusal.MALFORMED)

    @Test
    fun `INV-AC-16296 decode of encode under the same filter and sort returns the position`() {
        positions.forEach {
            ListCursor.decode(ListCursor.encode(it, filter, sort), filter, sort) shouldBe
                CursorDecoding.Resumed(it)
        }
    }

    @Test
    fun `INV-AC-16296 another filter is refused as FILTER_MISMATCH`() {
        val token = ListCursor.encode(positions[0], "direction=PURCHASE", sort)
        ListCursor.decode(token, "direction=SALE", sort) shouldBe CursorDecoding.Refused(CursorRefusal.FILTER_MISMATCH)
    }

    @Test
    fun `INV-AC-16296 another sort is refused as FILTER_MISMATCH`() {
        val token = ListCursor.encode(positions[0], filter, "-dueDate")
        ListCursor.decode(token, filter, "dueDate") shouldBe CursorDecoding.Refused(CursorRefusal.FILTER_MISMATCH)
    }

    @Test
    fun `INV-AC-16296 a token with one character changed in the binding is FILTER_MISMATCH`() {
        val parts = unb64(ListCursor.encode(positions[0], filter, sort)).split("|").toMutableList()
        parts[1] = (if (parts[1][0] == 'a') 'b' else 'a') + parts[1].substring(1)
        ListCursor.decode(b64(parts.joinToString("|")), filter, sort) shouldBe
            CursorDecoding.Refused(CursorRefusal.FILTER_MISMATCH)
    }

    @Test
    fun `INV-AC-16296 empty, blank, invalid and wrong-layout tokens are MALFORMED`() {
        val b = binding(filter, sort)
        listOf(
            "",
            " ",
            "!!!",
            b64("v1|x"),
            b64("v2|$b|a|b|1"),
            b64("v1|$b|a|b|0"),
            b64("v1|$b|a|b|x"),
            b64("v1|$b|a|b|-1"),
            b64("v1|$b|a|b|99999999999"),
            b64("v1|$b||b|1"),
            b64("v1|$b|a||1"),
            b64("v1|$b|a|b|c|1"),
        ).forEach { ListCursor.decode(it, filter, sort) shouldBe malformed() }
    }

    @Test
    fun `INV-AC-16296 a docUuid containing a bar built by hand is MALFORMED`() {
        ListCursor.decode(b64("v1|${binding(filter, sort)}|a|b|c|1"), filter, sort) shouldBe malformed()
    }

    @Test
    fun `INV-AC-16296 decode never throws for random text`() {
        val random = Random(16296)
        repeat(1000) {
            val text = String(CharArray(random.nextInt(0, 60)) { random.nextInt(0, 0x2FF).toChar() })
            ListCursor.decode(text, filter, sort)
            ListCursor.decode(b64(text), filter, sort)
        }
    }

    @Test
    fun `INV-AC-16296 the token is URL-safe and deterministic`() {
        val token = ListCursor.encode(positions[0], filter, sort)
        token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' } shouldBe true
        ListCursor.encode(positions[0], filter, sort) shouldBe token
    }

    @Test
    fun `INV-AC-16296 an invalid position throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { CursorPosition("a|b", "d", 1) }
        assertThrows<IllegalArgumentException> { CursorPosition("a", "d|e", 1) }
        assertThrows<IllegalArgumentException> { CursorPosition("a", "d", 0) }
        assertThrows<IllegalArgumentException> { CursorPosition(" ", "d", 1) }
    }
}
