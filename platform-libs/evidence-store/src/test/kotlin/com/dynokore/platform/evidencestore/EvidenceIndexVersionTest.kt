package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class EvidenceIndexVersionTest {
    @Test
    fun `INV-AC-20746 the default version is null and EMPTY is unchanged`() {
        assertNull(EvidenceIndex().version)
        assertNull(EvidenceIndex.EMPTY.version)
        assertEquals(EvidenceIndex(), EvidenceIndex.EMPTY)
    }

    @Test
    fun `INV-AC-20746 two indexes that differ only in version are not equal`() {
        assertNotEquals(EvidenceIndex(version = 3), EvidenceIndex(version = 4))
        assertNotEquals(EvidenceIndex(version = 3), EvidenceIndex.EMPTY)
        assertEquals(EvidenceIndex(version = 3), EvidenceIndex(version = 3))
    }

    @Test
    fun `INV-AC-20746 EvidenceRecord equals sees a version difference`() {
        val tenant = TenantId(UUID.randomUUID())
        val at = Instant.parse("2026-11-12T00:00:00Z")
        val content = "c".toByteArray()
        val hash = HashChain.link(null, content)

        fun record(index: EvidenceIndex) =
            EvidenceRecord(
                tenant,
                EntityRef("e"),
                PeriodRef("p"),
                EvidenceType.DECISION_TRACE,
                0L,
                content,
                hash,
                null,
                at,
                index,
            )
        assertNotEquals(record(EvidenceIndex(version = 3)), record(EvidenceIndex.EMPTY))
        assertEquals(record(EvidenceIndex(version = 3)), record(EvidenceIndex(version = 3)))
    }
}
