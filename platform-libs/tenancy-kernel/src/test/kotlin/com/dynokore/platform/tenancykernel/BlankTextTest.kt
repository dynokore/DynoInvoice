package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/** Pins the shared blank set and actor rule code point by code point (INV-Q-4614 (A), INV-Q-4622 (A)). */
class BlankTextTest {
    @Test
    fun `every listed code point and every range end is blank`() {
        val notBlank = BLANK_SET.flatMap { listOf(it.first, it.last) }.filterNot { BlankText.isBlank(text(it)) }
        assertEquals(emptyList<String>(), notBlank.map(::hex))
        assertTrue(BlankText.isBlank(null))
        assertTrue(BlankText.isBlank(""))
        assertTrue(BlankText.isBlank(" \u00A0\t\u3000\r\n\uFEFF\u0001\u009F"))
    }

    @Test
    fun `the neighbours of the set and a letter are not blank`() {
        listOf(0x21, 0xA1, 0x200B, 0x2060, 'a'.code).forEach { assertFalse(BlankText.isBlank(text(it)), hex(it)) }
        assertFalse(BlankText.isBlank(" \t x \n "))
    }

    @Test
    fun `the blank set is exactly the listed code points, over every code point`() {
        val differs =
            (0x0001..0x10FFFF)
                .filterNot { it in 0xD800..0xDFFF }
                .filter { cp -> BlankText.isBlank(text(cp)) != BLANK_SET.any { cp in it } }
        assertEquals(emptyList<String>(), differs.map(::hex))
    }

    @Test
    fun `an actor is not blank and holds no ISO control character anywhere`() {
        val controls = (0x0000..0x001F) + (0x007F..0x009F)
        val accepted = controls.filter { BlankText.isActor("a${text(it)}b") }.map(::hex)
        assertEquals(emptyList<String>(), accepted)
        val probes = listOf("a\tb", "\nx", "x\r", "x\u0085", "\u001B[31mx", "x\u009B", "\t", "\uFEFF", "")
        assertEquals(emptyList<String>(), probes.filter { BlankText.isActor(it) })
        assertFalse(BlankText.isActor(null))
        listOf("x\u200By", "CASE 5521", " user-a ", "u".repeat(255), "svc:x/job:y", "x\u00A0y").forEach {
            assertTrue(BlankText.isActor(it), it)
        }
    }

    private fun text(cp: Int) = String(Character.toChars(cp))

    private fun hex(cp: Int) = "U+%04X".format(Locale.ROOT, cp)

    private companion object {
        /** The set of coordinator ruling INV-Q-4614: ISO controls plus the caseId whitespace set (INV-Q-4534). */
        val BLANK_SET =
            listOf(
                0x0001..0x001F,
                0x007F..0x009F,
                0x0020..0x0020,
                0x00A0..0x00A0,
                0x1680..0x1680,
                0x2000..0x200A,
                0x2028..0x2028,
                0x2029..0x2029,
                0x202F..0x202F,
                0x205F..0x205F,
                0x3000..0x3000,
                0xFEFF..0xFEFF,
            )
    }
}
