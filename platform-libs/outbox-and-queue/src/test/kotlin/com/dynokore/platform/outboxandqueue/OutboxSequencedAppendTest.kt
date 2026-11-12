package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class OutboxSequencedAppendTest {
    private class AppendOnly : OutboxWriter {
        override fun append(
            events: List<OutboundEvent>,
            context: EventContext,
        ): List<Envelope> = emptyList()
    }

    @Test
    fun `INV-AC-20996 a writer that implements only append refuses appendSequenced`() {
        val doc = UUID.randomUUID()
        val context =
            EventContext(
                tenant = TenantId(UUID.randomUUID()),
                entity = EntityRef("entity-1"),
                docUuid = DocUuid(doc),
                transactionDate = LocalDate.of(2026, 9, 30),
                aggregateKey = doc.toString(),
                aggregateVersion = null,
                correlation = CorrelationId("corr"),
                causation = CausationId("cause"),
            )
        var calls = 0
        val thrown =
            assertThrows(UnsupportedOperationException::class.java) {
                AppendOnly().appendSequenced("X", context) {
                    calls++
                    "{}"
                }
            }
        assertEquals("${AppendOnly::class.java.name} does not append sequenced events", thrown.message)
        assertEquals(0, calls)
    }
}
