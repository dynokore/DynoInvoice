package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessionEvents
import com.dynokore.gst.access.application.breakglass.BreakGlassSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** INV-Q-9119 A, INV-AC-9303: the status vocabulary of the BreakGlassSessionChanged payload and its port. */
class BreakGlassSessionEventsTest {
    private val unlockedAt = Instant.parse("2026-11-05T01:00:00Z")

    private fun session() =
        BreakGlassSession(
            ref = "unlock-1",
            tenantRef = "tenant-1",
            sessionHours = 4,
            unlockedAt = unlockedAt,
            expiresAt = unlockedAt.plusSeconds(4 * 3600L),
            approvedBy = listOf("a", "b"),
            sealed = false,
        )

    @Test
    fun `INV-AC-16955 status entries are OPEN then SEALED`() {
        assertEquals(listOf("OPEN", "SEALED"), BreakGlassSessionStatus.entries.map { it.name })
    }

    @Test
    fun `INV-AC-16955 valueOf refuses UNLOCKED and lower case open`() {
        assertThrows(IllegalArgumentException::class.java) { BreakGlassSessionStatus.valueOf("UNLOCKED") }
        assertThrows(IllegalArgumentException::class.java) { BreakGlassSessionStatus.valueOf("open") }
    }

    @Test
    fun `INV-AC-16955 a recording implementation receives session and status unchanged in call order`() {
        val recorded = mutableListOf<Pair<BreakGlassSession, BreakGlassSessionStatus>>()
        val events = BreakGlassSessionEvents { session, status -> recorded.add(session to status) }
        val open = session()
        val sealed = open.copy(sealed = true)

        events.changed(open, BreakGlassSessionStatus.OPEN)
        events.changed(sealed, BreakGlassSessionStatus.SEALED)

        assertEquals(
            listOf(open to BreakGlassSessionStatus.OPEN, sealed to BreakGlassSessionStatus.SEALED),
            recorded,
        )
    }
}
