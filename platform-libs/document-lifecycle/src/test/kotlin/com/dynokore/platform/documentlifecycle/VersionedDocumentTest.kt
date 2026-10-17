package com.dynokore.platform.documentlifecycle

import com.dynokore.platform.documentlifecycle.Fixtures.docAt
import com.dynokore.platform.documentlifecycle.Fixtures.states
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VersionedDocumentTest {
    @Test
    fun `logical id is non-blank and at most 128 characters`() {
        LogicalId("x".repeat(128))
        assertThrows(IllegalArgumentException::class.java) { LogicalId("x".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { LogicalId("") }
        assertThrows(IllegalArgumentException::class.java) { LogicalId("   ") }
    }

    @Test
    fun `versions are numbered 1, 2, 3 without gaps in the initial state`() {
        val doc =
            Fixtures.lifecycle
                .open(Fixtures.id())
                .addVersion()
                .addVersion()
        assertEquals(listOf(1, 2, 3), doc.versions.map { it.number })
        assertEquals(listOf("NEW", "NEW", "NEW"), states(doc))
    }

    @Test
    fun `a resubmission is version n+1 while version n keeps its state`() {
        val doc = docAt(listOf("READY", "QUEUED", "SENT", "ACKNOWLEDGED")).addVersion()
        assertEquals(listOf("ACKNOWLEDGED", "NEW"), states(doc))
    }

    @Test
    fun `an illegal transition names the id, version, from and to`() {
        val doc = docAt(listOf("READY"))
        val e = assertThrows(IllegalTransitionException::class.java) { doc.transition(1, "SENT") }
        assertEquals(Fixtures.id(), e.id)
        assertEquals(1, e.version)
        assertEquals("READY", e.from)
        assertEquals("SENT", e.to)
        listOf("doc-1", "1", "READY", "SENT").forEach { assert(e.message!!.contains(it)) }
    }

    @Test
    fun `a terminal state has no way out`() {
        val doc = docAt(listOf("READY", "CANCELLED"))
        assertThrows(IllegalTransitionException::class.java) { doc.transition(1, "READY") }
    }

    @Test
    fun `the document is immutable`() {
        val before = docAt(listOf("READY"))
        val after = before.transition(1, "QUEUED")
        assertEquals(listOf("READY"), states(before))
        assertEquals(listOf("QUEUED"), states(after))
        val grown = before.addVersion()
        assertEquals(1, before.versions.size)
        assertEquals(2, grown.versions.size)
    }

    @Test
    fun `an unknown version is refused`() {
        assertThrows(IllegalArgumentException::class.java) { docAt(listOf("READY")).transition(2, "QUEUED") }
        assertThrows(IllegalArgumentException::class.java) { docAt(listOf("READY")).transition(0, "QUEUED") }
    }
}
