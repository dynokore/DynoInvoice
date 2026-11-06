package com.dynokore.gst.masterdata.domain.counterparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** INV-Q-17217 A: the three UEN forms, compared with the code by equality; every look-alike is refused. */
class UenFormatTest {
    @Test
    fun `the five published forms are accepted`() {
        listOf("53012345X", "201912345A", "T08LL0001B", "S99FC0001C", "R12PF0003D").forEach {
            assertEquals(true, UenFormat.accepts(it), it)
            assertEquals(it, UenFormat.parse(it)?.value)
        }
    }

    @Test
    fun `each look-alike is refused as given`() {
        val refused =
            listOf(
                "5301234X", // 7 digits
                "5301234567X", // 10 digits
                "53012345", // no letter
                "53012345x", // lower case
                " 53012345X", // leading space
                "53012345X ", // trailing space
                "53012345X\n", // followed by a line feed
                "U08LL0001B", // prefix U
                "T8LL0001B", // 1-digit year
                "T08L10001B", // a digit in the entity type
                "\uFF15\uFF13\uFF10\uFF11\uFF12\uFF13\uFF14\uFF15X", // full-width digits
                "",
                "NA",
            )
        refused.forEach {
            assertEquals(false, UenFormat.accepts(it), "[$it]")
            assertNull(UenFormat.parse(it), "[$it]")
        }
    }
}
