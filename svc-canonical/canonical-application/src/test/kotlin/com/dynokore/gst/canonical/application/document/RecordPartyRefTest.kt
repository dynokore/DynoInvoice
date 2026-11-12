package com.dynokore.gst.canonical.application.document

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class RecordPartyRefTest {
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val rows = linkedMapOf<UUID, String>()
    private val calls = mutableListOf<Pair<UUID, String>>()
    private val record =
        RecordPartyRef(
            object : DocumentPartyRefs {
                override fun upsert(
                    docUuid: UUID,
                    partyRef: String,
                ) {
                    calls += docUuid to partyRef
                    rows[docUuid] = partyRef
                }
            },
        )

    @Test
    fun `INV-AC-21358 a record stores the reference once, a repeat leaves it equal and an amendment replaces it`() {
        record.record(d1, "P1")
        rows shouldBe mapOf(d1 to "P1")
        record.record(d1, "P1")
        rows shouldBe mapOf(d1 to "P1")
        record.record(d1, "P2")
        rows shouldBe mapOf(d1 to "P2")
        calls shouldBe listOf(d1 to "P1", d1 to "P1", d1 to "P2")
    }

    @Test
    fun `INV-AC-21358 a blank or over-long reference is refused with IllegalArgumentException and writes nothing`() {
        listOf("", " ", "\t\n", "x".repeat(41)).forEach { bad ->
            assertThrows<IllegalArgumentException> { record.record(d2, bad) }
        }
        calls shouldBe emptyList()
        record.record(d2, "x".repeat(40))
        rows shouldBe mapOf(d2 to "x".repeat(40))
    }
}
