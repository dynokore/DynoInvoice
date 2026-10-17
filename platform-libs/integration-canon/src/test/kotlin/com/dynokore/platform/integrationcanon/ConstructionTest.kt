package com.dynokore.platform.integrationcanon

import com.dynokore.platform.integrationcanon.Fixtures.a
import com.dynokore.platform.integrationcanon.Fixtures.b
import com.dynokore.platform.integrationcanon.Fixtures.d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConstructionTest {
    private fun refused(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test
    fun `version codes`() {
        refused { SpecVersion("") }
        refused { SpecVersion("  ") }
        refused { SpecVersion("x".repeat(65)) }
        assertEquals("x".repeat(64), SpecVersion("x".repeat(64)).code)
    }

    @Test
    fun `release order refusals`() {
        refused { ReleaseOrder.of(emptyList()) }
        refused { ReleaseOrder.of(listOf(a, b, a)) }
        assertEquals(2, ReleaseOrder.of(listOf(SpecVersion("v1"), SpecVersion("V1"))).versions.size)
    }

    @Test
    fun `isOlder follows list position and refuses unknown versions`() {
        assertTrue(Fixtures.order.isOlder(a, b))
        assertFalse(Fixtures.order.isOlder(b, a))
        assertFalse(Fixtures.order.isOlder(a, a))
        refused { Fixtures.order.isOlder(a, SpecVersion("spec-z")) }
        refused { Fixtures.order.isOlder(SpecVersion("spec-z"), a) }
    }

    @Test
    fun `calendar refusals`() {
        val e = AcceptanceEntry(a, d(2024, 1, 1), null)
        refused { AcceptanceCalendar.of(Fixtures.order, emptyList()) }
        refused {
            AcceptanceCalendar.of(
                Fixtures.order,
                listOf(AcceptanceEntry(SpecVersion("spec-z"), d(2024, 1, 1), null)),
            )
        }
        refused { AcceptanceCalendar.of(Fixtures.order, listOf(e, e)) }
        refused { AcceptanceEntry(a, d(2024, 1, 2), d(2024, 1, 1)) }
        assertEquals(
            listOf(a),
            AcceptanceCalendar
                .of(
                    Fixtures.order,
                    listOf(AcceptanceEntry(a, d(2024, 1, 1), d(2024, 1, 1))),
                ).acceptedOn(d(2024, 1, 1)),
        )
    }
}
