package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * [OutboxAuditWriter] as the [AuditStager] of a service with an outbox (coordinator rulings INV-Q-4254 A and
 * INV-Q-4277; INV-T-3983): [OutboxAuditWriter.stage] builds the `AuditEntryRecorded` outbox row that
 * [OutboxAuditWriter.record] writes, over outbox-and-queue's stage call, and inserts nothing.
 */
@Tag("integration")
class OutboxAuditStageIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `stage builds the outbox row that record writes and inserts nothing`(image: String) {
        AuditHarness(image).use { h ->
            val context = platformContext("config:VP-78:INSTALLATION")
            val entry = AuditEntry("operator-1", "setPolicy", "INSTALLATION", "because", """{"value": null}""")
            val staged = h.inPlatform { h.audit.stage(entry, context) }
            assertEquals(0, h.auditEntries(context.aggregateKey), "stage inserts nothing")

            val recorded = h.inPlatform { h.audit.record(entry, context) }
            // Same clock and context: only the UUIDv7's random bits differ.
            assertEquals(recorded, staged.envelope.copy(id = recorded.id))
            assertEquals(7, UUID.fromString(staged.envelope.id.value).version())
            assertEquals(entry, staged.entry)
            assertEquals("""{"value":null}""", staged.priorStateJson)
            assertEquals(
                1,
                h.count(
                    "select count(*) from ${AuditHarness.SCHEMA}.outbox where id = ? and type = ? and data = ?::jsonb",
                    recorded.id.value,
                    OutboxAuditWriter.EVENT_TYPE,
                    staged.envelope.dataJson,
                ),
                "record wrote the staged row",
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `only an unsequenced entry is staged`(image: String) {
        AuditHarness(image).use { h ->
            val entry = AuditEntry("operator-1", "setPolicy", "INSTALLATION")
            val versioned = platformContext("config:VP-78:INSTALLATION").copy(aggregateVersion = 1)
            assertThrows(IllegalArgumentException::class.java) { h.inPlatform { h.audit.stage(entry, versioned) } }
            val tenant = UUID.randomUUID()
            val document = h.context(tenant, doc = UUID.randomUUID())
            assertThrows(IllegalArgumentException::class.java) { h.asTenant(tenant) { h.audit.stage(entry, document) } }
        }
    }

    private fun <T> AuditHarness.inPlatform(block: () -> T): T {
        val actor = "operator-1"
        return tenants.bindPlatform(actor) { boundary.inTransaction(block) }
    }

    private fun platformContext(key: String) = EventContext(
        tenant = TenantId.PLATFORM,
        entity = EntityRef.PLATFORM,
        docUuid = null,
        transactionDate = null,
        aggregateKey = key,
        aggregateVersion = null,
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
    )
}
