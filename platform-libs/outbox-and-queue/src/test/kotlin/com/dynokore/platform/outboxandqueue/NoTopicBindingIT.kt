package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TenantPredicateViolation
import com.dynokore.platform.tenancykernel.TenantContextImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * The event binding of a context with no [EventCatalogue] (coordinator ruling INV-Q-5851): its DSLContext declares no
 * topic table, so a publish is refused and writes nothing; the same binding with the topic declared publishes the row.
 */
@Tag("integration")
class NoTopicBindingIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a binding with no topic table refuses a publish and one with the topic declared writes the row`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val producer = harness.payload
            producer.inTx(ServiceKit.TENANT) {
                producer.writer.append(
                    listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":1}""")),
                    producer.docContext(ServiceKit.TENANT, UUID.randomUUID()),
                )
            }
            harness.drain(producer.relay)
            val envelope = harness.logOf(TOPIC).single().copy(id = EventId(UUID.randomUUID().toString()))
            val table = "eventlog.${EventLogSchema.tableName(TOPIC)}"
            val sql = "select count(*) from $table where event_id = ?"

            val scope = harness.fixture.eventLogScope(TenantContextImpl())
            val bare =
                PostgresEventBinding(
                    scope,
                    JooqKit.eventLog(scope, EventCatalogue(emptyList(), emptyList(), emptyList())),
                )
            assertThrows(TenantPredicateViolation::class.java) { bare.publish(TOPIC, envelope) }
            assertEquals(0, harness.count(sql, envelope.id.value), "the refused publish wrote a row")

            harness.binding().publish(TOPIC, envelope)
            assertEquals(1, harness.count(sql, envelope.id.value))
        }
    }

    private companion object {
        const val TOPIC = "gst.payload.payload-ready.v1"
    }
}
