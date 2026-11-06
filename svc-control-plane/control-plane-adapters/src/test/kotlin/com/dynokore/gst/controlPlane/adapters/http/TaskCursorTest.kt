package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.accreditation.TaskCursorKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class TaskCursorTest {
    private val cursor = TaskCursor(ByteArray(32) { it.toByte() })
    private val position = TaskCursorKey(Instant.parse("2026-10-19T08:00:00Z"), "TASK-1|x")

    @Test
    fun `a cursor round-trips for the same principal and filter`() {
        val text = cursor.encode(position, "rita", "OPEN")
        cursor.decode(text, "rita", "OPEN") shouldBe position
        cursor.decode(cursor.encode(position, "rita", null), "rita", null) shouldBe position
    }

    @Test
    fun `a cursor for another principal, another filter, a flipped byte or a foreign key does not decode`() {
        val text = cursor.encode(position, "rita", "OPEN")
        cursor.decode(text, "bob", "OPEN") shouldBe null
        cursor.decode(text, "rita", "CONCLUDED") shouldBe null
        cursor.decode(text, "rita", null) shouldBe null
        cursor.decode(text.dropLast(1) + (if (text.last() == 'A') "B" else "A"), "rita", "OPEN") shouldBe null
        cursor.decode(text.substringBefore('.'), "rita", "OPEN") shouldBe null
        cursor.decode("", "rita", "OPEN") shouldBe null
        cursor.decode("a.b.c", "rita", "OPEN") shouldBe null
        TaskCursor(ByteArray(32) { 7 }).decode(text, "rita", "OPEN") shouldBe null
    }

    @Test
    fun `a short key is refused`() {
        shouldThrow<IllegalArgumentException> { TaskCursor(ByteArray(31)) }
    }
}
