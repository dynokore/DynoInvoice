package com.dynokore.platform.documentlifecycle

import com.dynokore.platform.documentlifecycle.Fixtures.docAt
import com.dynokore.platform.documentlifecycle.Fixtures.states
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SupersessionTest {
    @Test
    fun `version 2 reaching READY cancels a queued version 1 (INV-AC-609)`() {
        val doc = docAt(listOf("READY", "QUEUED")).addVersion().transition(2, "READY")
        assertEquals(listOf("CANCELLED", "READY"), states(doc))
    }

    @Test
    fun `READY and AWAITING_PRECEDING older versions are cancelled too`() {
        assertEquals(listOf("CANCELLED", "READY"), states(docAt(listOf("READY")).addVersion().transition(2, "READY")))
        assertEquals(
            listOf("CANCELLED", "READY"),
            states(docAt(listOf("AWAITING_PRECEDING")).addVersion().transition(2, "READY")),
        )
    }

    @Test
    fun `a version 1 already sent is left unchanged`() {
        val doc = docAt(listOf("READY", "QUEUED", "SENT")).addVersion().transition(2, "READY")
        assertEquals(listOf("SENT", "READY"), states(doc))
    }

    @Test
    fun `an older version reaching the trigger does not move a newer one`() {
        val doc =
            Fixtures.lifecycle
                .open(Fixtures.id())
                .addVersion()
                .transition(2, "READY")
                .transition(1, "READY")
        assertEquals(listOf("READY", "READY"), states(doc))
    }

    @Test
    fun `version 1 stays ACKNOWLEDGED while version 2 is queued or sent, then is superseded (INV-AC-1693)`() {
        val acked = docAt(listOf("READY", "QUEUED", "SENT", "ACKNOWLEDGED"))
        val queued = acked.addVersion().transition(2, "READY").transition(2, "QUEUED")
        assertEquals(listOf("ACKNOWLEDGED", "QUEUED"), states(queued))
        val sent = queued.transition(2, "SENT")
        assertEquals(listOf("ACKNOWLEDGED", "SENT"), states(sent))
        assertEquals(listOf("SUPERSEDED", "ACKNOWLEDGED"), states(sent.transition(2, "ACKNOWLEDGED")))
    }

    @Test
    fun `RESUBMITTING is not a state and a transition to it is refused (INV-AC-1693)`() {
        assert("RESUBMITTING" !in Fixtures.model.states)
        val doc = docAt(listOf("READY", "QUEUED", "SENT", "ACKNOWLEDGED"))
        assertThrows(IllegalTransitionException::class.java) { doc.transition(1, "RESUBMITTING") }
    }

    @Test
    fun `a rule moving a pair the model does not allow is refused at construction`() {
        val bad = SupersessionRule("READY", setOf("SENT"), "CANCELLED")
        assertThrows(IllegalArgumentException::class.java) { SupersessionRules(Fixtures.model, listOf(bad)) }
    }

    @Test
    fun `a rule naming an unknown state is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            SupersessionRules(Fixtures.model, listOf(SupersessionRule("NOPE", setOf("READY"), "CANCELLED")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupersessionRules(Fixtures.model, listOf(SupersessionRule("READY", setOf("NOPE"), "CANCELLED")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupersessionRules(Fixtures.model, listOf(SupersessionRule("READY", setOf("READY"), "NOPE")))
        }
    }
}
