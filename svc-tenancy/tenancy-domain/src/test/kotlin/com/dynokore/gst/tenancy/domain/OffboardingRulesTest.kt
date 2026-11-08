package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Export manifest: the manifest part of the record state, and the holder rule of INV-AC-2056, on the pure rules. */
class OffboardingRulesTest {
    private fun manifest(
        state: ExportState,
        contents: Set<ExportContent>,
    ) = ExportManifest("EXP-1", state, contents)

    @Test
    fun `export manifest - READY with all six members is COMPLETED`() {
        assertEquals(
            OffboardingState.COMPLETED,
            OffboardingRules.recordState(manifest(ExportState.READY, ExportContent.entries.toSet())),
        )
    }

    @Test
    fun `export manifest - READY with five members is not COMPLETED`() {
        val five = ExportContent.entries.drop(1).toSet()
        assertEquals(OffboardingState.REQUESTED, OffboardingRules.recordState(manifest(ExportState.READY, five)))
    }

    @Test
    fun `export manifest - a FAILED manifest is FAILED even with six members`() {
        assertEquals(
            OffboardingState.FAILED,
            OffboardingRules.recordState(manifest(ExportState.FAILED, ExportContent.entries.toSet())),
        )
    }

    @Test
    fun `export manifest - a REQUESTED manifest with six members is not COMPLETED`() {
        assertEquals(
            OffboardingState.REQUESTED,
            OffboardingRules.recordState(manifest(ExportState.REQUESTED, ExportContent.entries.toSet())),
        )
    }

    @Test
    fun `INV-AC-2056 a blank name or blank email after trimming is a blank holder`() {
        assertTrue(OffboardingRules.holderIsBlank(RetentionCustodyHolder("  \t", "a@b.example")))
        assertTrue(OffboardingRules.holderIsBlank(RetentionCustodyHolder("Ledger", " \n ")))
        assertFalse(OffboardingRules.holderIsBlank(RetentionCustodyHolder("Ledger", "a@b.example")))
    }

    @Test
    fun `only a FAILED record leaves room for a new request`() {
        assertTrue(OffboardingRules.blocksNewRequest(OffboardingState.REQUESTED))
        assertTrue(OffboardingRules.blocksNewRequest(OffboardingState.COMPLETED))
        assertFalse(OffboardingRules.blocksNewRequest(OffboardingState.FAILED))
    }
}
