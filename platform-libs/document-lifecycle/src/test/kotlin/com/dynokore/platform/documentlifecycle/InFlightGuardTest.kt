package com.dynokore.platform.documentlifecycle

import com.dynokore.platform.documentlifecycle.Fixtures.docAt
import com.dynokore.platform.documentlifecycle.Fixtures.states
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InFlightGuardTest {
    @Test
    fun `version 2 cannot enter QUEUED while version 1 is in RETRY (INV-AC-610)`() {
        val doc = docAt(listOf("READY", "QUEUED", "SENT", "RETRY"), listOf("READY"))
        assertEquals(listOf("RETRY", "READY"), states(doc)) // reaching READY is not flight, and RETRY is not cancelled
        val e = assertThrows(InFlightConflictException::class.java) { doc.transition(2, "QUEUED") }
        assertEquals(2, e.version)
        assertEquals(1, e.otherVersion)
        assertEquals(Fixtures.id(), e.id)
    }

    @Test
    fun `every in-flight state of version 1 blocks version 2`() {
        listOf(
            // QUEUED is absent: version 2 reaching READY cancels an unclaimed queued version 1
            listOf("READY", "QUEUED", "SENT"),
            listOf("READY", "QUEUED", "SENT", "SENT_UNCONFIRMED"),
            listOf("READY", "QUEUED", "SENT", "RETRY"),
        ).forEach { path ->
            val doc = docAt(path, listOf("READY"))
            assertThrows(InFlightConflictException::class.java, { doc.transition(2, "QUEUED") }, path.toString())
        }
    }

    @Test
    fun `version 2 can enter QUEUED once version 1 is out of every in-flight state`() {
        val doc = docAt(listOf("READY", "QUEUED", "SENT", "RETRY", "CANCELLED"), listOf("READY"))
        assertEquals(listOf("CANCELLED", "QUEUED"), states(doc.transition(2, "QUEUED")))
        val acked = docAt(listOf("READY", "QUEUED", "SENT", "ACKNOWLEDGED"), listOf("READY"))
        assertEquals(listOf("ACKNOWLEDGED", "QUEUED"), states(acked.transition(2, "QUEUED")))
    }

    @Test
    fun `a refused move leaves the document unchanged`() {
        val doc = docAt(listOf("READY", "QUEUED", "SENT"), listOf("READY"))
        assertThrows(InFlightConflictException::class.java) { doc.transition(2, "QUEUED") }
        assertEquals(listOf("SENT", "READY"), states(doc))
    }

    @Test
    fun `one version moving between in-flight states is not a conflict with itself`() {
        val doc = docAt(listOf("READY", "QUEUED", "SENT", "RETRY", "SENT", "SENT_UNCONFIRMED"))
        assertEquals(listOf("SENT_UNCONFIRMED"), states(doc))
    }
}
