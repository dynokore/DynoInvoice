package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.accreditation.TaskCursorKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class TaskCursorTest {
    private val key = ByteArray(32) { it.toByte() }
    private val cursor = TaskCursor { key }
    private val position = TaskCursorKey(Instant.parse("2026-10-19T08:00:00Z"), "TASK-1|x")

    @Test
    fun `a cursor round-trips for the same principal and filter`() {
        val text = cursor.encode(key, position, "rita", "OPEN")
        cursor.decode(key, text, "rita", "OPEN") shouldBe position
        cursor.decode(key, cursor.encode(key, position, "rita", null), "rita", null) shouldBe position
    }

    @Test
    fun `a cursor for another principal, another filter, a flipped byte or a foreign key does not decode`() {
        val text = cursor.encode(key, position, "rita", "OPEN")
        cursor.decode(key, text, "bob", "OPEN") shouldBe null
        cursor.decode(key, text, "rita", "CONCLUDED") shouldBe null
        cursor.decode(key, text, "rita", null) shouldBe null
        cursor.decode(key, text.dropLast(1) + (if (text.last() == 'A') "B" else "A"), "rita", "OPEN") shouldBe null
        cursor.decode(key, text.substringBefore('.'), "rita", "OPEN") shouldBe null
        cursor.decode(key, "", "rita", "OPEN") shouldBe null
        cursor.decode(key, "a.b.c", "rita", "OPEN") shouldBe null
        cursor.decode(ByteArray(32) { 7 }, text, "rita", "OPEN") shouldBe null
    }

    @Test
    fun `a short key is refused at every read and the source is read each time`() {
        var current = ByteArray(31)
        val reading = TaskCursor { current }
        shouldThrow<IllegalStateException> { reading.key() }
        current = ByteArray(32)
        reading.key().size shouldBe 32
        current = ByteArray(32) { 1 }
        reading.key()[0] shouldBe 1
        shouldThrow<NoSuchElementException> { TaskCursor { throw NoSuchElementException("no file") }.key() }
    }
}
