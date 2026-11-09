package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure rules of the lifecycle changes. */
class LifecycleTest {
    private val met = GoLiveFacts(emptyList(), CertificationOutcome.PASSED, emptyList(), emptyList())

    @Test
    fun `go-live gate with every item met names nothing`() {
        assertEquals(emptyList<String>(), GoLiveGate.unmet(met))
    }

    @Test
    fun `go-live gate names each unmet item`() {
        val unmet =
            GoLiveGate.unmet(
                GoLiveFacts(listOf("T1"), CertificationOutcome.FAILED, listOf("s1", "s2"), listOf("ISSUANCE_MODE")),
            )
        assertEquals(3, unmet.size)
        assertTrue(unmet[0].contains("T1"))
        assertTrue(unmet[1].contains("s1") && unmet[1].contains("s2"))
        assertTrue(unmet[2].contains("ISSUANCE_MODE"))
    }

    @Test
    fun `go-live gate without a certification record names the sandbox certification`() {
        assertTrue(GoLiveGate.unmet(met.copy(certification = null)).single().contains("sandbox certification"))
    }

    @Test
    fun `go-live is a repeat only for the exact state LIVE`() {
        assertTrue(LifecycleRules.goLiveIsRepeat("LIVE"))
        assertFalse(LifecycleRules.goLiveIsRepeat("live"))
        assertFalse(LifecycleRules.goLiveIsRepeat("SUSPENDED"))
    }

    @Test
    fun `deregistration is blocked by an unsubmitted document unless an acknowledgement is recorded`() {
        val ack = UnsubmittedOverride("identity:USR-004", "accepted")
        assertTrue(LifecycleRules.deregistrationBlocked(1, null))
        assertFalse(LifecycleRules.deregistrationBlocked(1, ack))
        assertFalse(LifecycleRules.deregistrationBlocked(0, null))
    }
}
