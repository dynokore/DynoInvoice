package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SandboxCertificationTest {
    private fun refusal(
        outcome: CertificationOutcome,
        scenarios: List<String>?,
    ) = CertificationRules.refusal(outcome, scenarios)

    @Test
    fun `FAILED with failedScenarios absent is refused`() = assertNotNull(refusal(CertificationOutcome.FAILED, null))

    @Test
    fun `FAILED with an empty list is refused`() = assertNotNull(refusal(CertificationOutcome.FAILED, emptyList()))

    @Test
    fun `PASSED with one failed scenario is refused`() =
        assertNotNull(refusal(CertificationOutcome.PASSED, listOf("a")))

    @Test
    fun `PASSED with an empty list is refused`() = assertNotNull(refusal(CertificationOutcome.PASSED, emptyList()))

    @Test
    fun `PASSED with failedScenarios absent is accepted`() = assertNull(refusal(CertificationOutcome.PASSED, null))

    @Test
    fun `FAILED with one item of 129 characters is refused and 128 is accepted`() {
        assertNotNull(refusal(CertificationOutcome.FAILED, listOf("x".repeat(129))))
        assertNull(refusal(CertificationOutcome.FAILED, listOf("x".repeat(128))))
        assertNotNull(refusal(CertificationOutcome.FAILED, listOf("")))
    }

    @Test
    fun `FAILED with the items a and b is accepted`() =
        assertNull(refusal(CertificationOutcome.FAILED, listOf("a", "b")))

    @Test
    fun `the trigger kinds are the five names by exact equality`() {
        assertEquals(5, StaleTrigger.entries.size)
        StaleTrigger.entries.forEach { assertEquals(it, StaleTrigger.parse(it.name)) }
        listOf("NEW_TRAINS", "new_train", "NEW_TRAIN ", "").forEach { assertNull(StaleTrigger.parse(it), it) }
    }

    @Test
    fun `the permission key is the ruled one`() =
        assertEquals("sandbox-certification.record", SandboxCertificationPermissions.RECORD)
}
