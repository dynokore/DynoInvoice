package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * Record topics are exempt from the docsequence rule (coordinator ruling INV-Q-3809, INV-REQ-1221 b4, INV-T-3385):
 * an AuditEntryRecorded is applied on inbox dedupe by event id only, with no docsequence check and no held-sequence
 * row, live and on dead-letter replay; a duplicate event id is still dropped. The record set comes from the
 * catalogue with no per-service wiring, and a declared set that differs fails construction. PostgreSQL 17 and 18.
 */
@Tag("integration")
class RecordTopicIT {
    private val audit = "gst.platform.audit-entry-recorded.v1"

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a record event with an older docsequence is kept and a duplicate event id is dropped`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val doc = UUID.randomUUID()
            val evidence = harness.evidence
            val scope = evidence.scope
            val types = harness.catalogue.recordTypes()
            assertEquals(setOf(TestCatalogue.AUDIT_ENTRY_RECORDED), types, "from record_topic in topics.yaml")
            // No recordTypes wiring: the consumer takes the record topics from the catalogue (fail-safe).
            val record = consumer(harness, scope, "evidence-svc", harness.catalogue)
            val plain = consumer(harness, scope, "plain-svc", harness.sequencedCatalogue)
            for (name in listOf("evidence-svc", "plain-svc")) {
                harness.fixture.runAsAdmin(
                    "insert into evidence.consumer_sequence values " +
                        "('$name', '/gst/payload-svc', '$doc', '${ServiceKit.TENANT}', 99)",
                )
            }
            repeat(2) { emit(harness, kit, doc) }

            assertEquals(PollResult(2, 0, 0, 0, 0), record.pollOnce(), "kept although 1, 2 <= held 99")
            assertEquals(PollResult(0, 0, 0, 2, 0), plain.pollOnce(), "the rule a non-record topic keeps")
            val rows = "select count(*) from evidence.consumer_sequence where consumer = 'evidence-svc'"
            assertEquals(1L, harness.count(rows))
            val held = "select last_value from evidence.consumer_sequence where consumer = 'evidence-svc'"
            assertEquals(99L, harness.count(held), "no held-sequence row written or moved")

            // Dead-letter replay: an older record event is applied; the same event id again is a duplicate.
            emit(harness, kit, doc)
            val raw = lastEnvelope(harness)
            val tenant = TenantId(ServiceKit.TENANT)
            assertEquals(ReplayOutcome.APPLIED, record.applyReplayed(raw, tenant))
            assertEquals(ReplayOutcome.DUPLICATE, record.applyReplayed(raw, tenant))
            assertEquals(PollResult(0, 0, 1, 0, 0), record.pollOnce(), "the live copy is deduped by event id")
            assertEquals(99L, harness.count(held))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `a declared record-type set that differs from the catalogue fails startup`(image: String) {
        OutboxHarness(image).use { harness ->
            val evidence = harness.evidence
            val scope = evidence.scope
            val types = harness.catalogue.recordTypes()
            consumer(harness, scope, "evidence-svc", harness.catalogue, types)
            for (declared in listOf(emptySet(), types + "OtherRecorded")) {
                assertThrows<IllegalStateException> {
                    consumer(harness, scope, "evidence-svc", harness.catalogue, declared)
                }
            }
        }
    }

    private fun consumer(
        harness: OutboxHarness,
        scope: TenantScope,
        name: String,
        catalogue: EventCatalogue,
        recordTypes: Set<String>? = null,
    ) = JdbcInboxConsumer(
        ConsumerSubscription(name, audit, "evidence", recordTypes = recordTypes),
        catalogue,
        harness.reader,
        harness.fixture.runtimeDataSource(),
        scope,
        object : EventHandler {
            override fun handle(event: Envelope) = HandleOutcome.APPLIED
        },
        harness.rebuildSink,
    )

    private fun emit(
        harness: OutboxHarness,
        kit: ServiceKit,
        doc: UUID,
    ) {
        kit.inTx(ServiceKit.TENANT) {
            val event = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, "{}")
            kit.writer.append(listOf(event), kit.docContext(ServiceKit.TENANT, doc))
        }
        harness.drain(kit.relay)
    }

    private fun lastEnvelope(harness: OutboxHarness): String =
        harness.fixture.adminDataSource().connection.use {
            it
                .createStatement()
                .executeQuery(
                    "select envelope::text from eventlog.gst_platform_audit_entry_recorded_v1 " +
                        "order by log_offset desc limit 1",
                ).use { rows ->
                    rows.next()
                    rows.getString(1)
                }
        }
}
