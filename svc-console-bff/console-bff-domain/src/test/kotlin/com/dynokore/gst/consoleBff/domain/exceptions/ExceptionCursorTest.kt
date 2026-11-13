package com.dynokore.gst.consoleBff.domain.exceptions

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

class ExceptionCursorTest {
    private val raised = Instant.parse("2026-11-01T08:30:00Z")

    private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun `INV-AC-21103 encode then decode returns the same key with a due date`() {
        val key = ExceptionSortKey(LocalDate.parse("2026-11-21"), raised, "x-2")
        ExceptionCursor.decode(ExceptionCursor.encode(key)) shouldBe key
    }

    @Test
    fun `INV-AC-21103 encode then decode returns the same key without a due date`() {
        val key = ExceptionSortKey(null, raised, "x-3")
        ExceptionCursor.decode(ExceptionCursor.encode(key)) shouldBe key
    }

    @Test
    fun `INV-AC-21103 decode returns null for an empty text`() {
        ExceptionCursor.decode("") shouldBe null
    }

    @Test
    fun `INV-AC-21103 decode returns null for text that is not base64url`() {
        ExceptionCursor.decode("not-a-cursor") shouldBe null
        ExceptionCursor.decode("a+b/c=") shouldBe null
    }

    @Test
    fun `INV-AC-21103 decode returns null for another version`() {
        ExceptionCursor.decode(b64("v2|2026-11-21|2026-11-01T08:30:00Z|x")) shouldBe null
    }

    @Test
    fun `INV-AC-21103 decode returns null for a bad date`() {
        ExceptionCursor.decode(b64("v1|2026-13-40|2026-11-01T08:30:00Z|x")) shouldBe null
        ExceptionCursor.decode(b64("v1|2026-11-21|not-an-instant|x")) shouldBe null
    }

    @Test
    fun `INV-AC-21103 decode returns null for an extra or missing field`() {
        ExceptionCursor.decode(b64("v1|2026-11-21|2026-11-01T08:30:00Z|x|y")) shouldBe null
        ExceptionCursor.decode(b64("v1|2026-11-21|2026-11-01T08:30:00Z")) shouldBe null
    }

    @Test
    fun `INV-AC-21103 decode returns null when the re-encoding differs from the text`() {
        val canonical = ExceptionCursor.encode(ExceptionSortKey(null, raised, "x"))
        ExceptionCursor.decode("$canonical=") shouldBe null
        ExceptionCursor.decode(b64("v1|-|2026-11-01T08:30:00.000Z|x")) shouldBe null
        ExceptionCursor.decode(b64("v1|-|2026-11-01T08:30:00+00:00|x")) shouldBe null
    }
}
