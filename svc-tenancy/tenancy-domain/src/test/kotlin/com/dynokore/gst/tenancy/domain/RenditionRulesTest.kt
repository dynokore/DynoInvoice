package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenditionRulesTest {
    @Test
    fun `attester equal to the caller is accepted, a different one is not`() {
        assertTrue(RenditionRules.attesterIsCaller("u-1", "u-1"))
        assertFalse(RenditionRules.attesterIsCaller("u-2", "u-1"))
        assertFalse(RenditionRules.attesterIsCaller("U-1", "u-1"))
        assertFalse(RenditionRules.attesterIsCaller("", ""))
    }

    @Test
    fun `renderer parses the three values exactly and refuses look-alikes`() {
        assertEquals(Renderer.AGGREGATOR, Renderer.parse("AGGREGATOR"))
        assertNull(Renderer.parse("AGGREGATORS"))
        assertNull(Renderer.parse("aggregator"))
        assertNull(Renderer.parse(" SELF"))
        assertNull(Renderer.parse(null))
    }

    @Test
    fun `an open finding requests again and a closed one changes nothing`() {
        assertEquals(AttestationState.REQUESTED_AGAIN, RenditionRules.stateAfterFinding(true))
        assertNull(RenditionRules.stateAfterFinding(false))
    }

    @Test
    fun `only a sequence above the stored one is newer`() {
        assertTrue(RenditionRules.isNewer(1, null))
        assertTrue(RenditionRules.isNewer(6, 5))
        assertFalse(RenditionRules.isNewer(5, 5))
        assertFalse(RenditionRules.isNewer(4, 5))
    }
}
