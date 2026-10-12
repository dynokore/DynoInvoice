package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** INV-Q-4036: `SqlIdentifier.require` returns only `^[a-z][a-z0-9_]{0,62}$` names and refuses everything else. */
class SqlIdentifierTest {
    @Test
    fun `returns a plain lower-case name unchanged, up to 63 characters`() {
        assertEquals("filing_fact", SqlIdentifier.require("filing_fact", "table"))
        assertEquals("a", SqlIdentifier.require("a", "table"))
        val longest = "a" + "b".repeat(62)
        assertEquals(longest, SqlIdentifier.require(longest, "table"))
    }

    @Test
    fun `refuses an upper-case name`() {
        assertRefused("Filing_fact")
    }

    @Test
    fun `refuses a quote`() {
        assertRefused("filing\"fact")
        assertRefused("filing'fact")
    }

    @Test
    fun `refuses a 64-character name`() {
        assertRefused("a" + "b".repeat(63))
    }

    @Test
    fun `refuses an empty string`() {
        assertRefused("")
    }

    @Test
    fun `refuses a leading underscore or digit, whitespace and statement text`() {
        assertRefused("_filing")
        assertRefused("1filing")
        assertRefused("filing fact")
        assertRefused("x; DROP TABLE y --")
    }

    @Test
    fun `names the identifier in the message`() {
        val error = assertThrows(IllegalArgumentException::class.java) { SqlIdentifier.require("Bad", "schema") }
        assertEquals("schema must be a plain lower-case identifier of at most 63 chars, was 'Bad'", error.message)
    }

    private fun assertRefused(name: String) {
        assertThrows(IllegalArgumentException::class.java) { SqlIdentifier.require(name, "table") }
    }
}
