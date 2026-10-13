package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * Consumer-driven contract test of the module API (done-when: contract tests pass), the same
 * equivalence as tenancy-kernel's and evidence-store's `ModuleApiConsumerContractTest`: a fake
 * `submission-svc` command written against only `-api` types ([TransactionBoundary], [OutboxWriter],
 * [EventContext]) runs against the real implementations on PostgreSQL 17 and 18.
 * INV-AC-2184 (rollback leaves no outbox row, commit leaves exactly its rows) and
 * INV-AC-1869 (full envelope; emitted only by the catalogued service).
 * `submission-svc` does not exist yet: [FakeDespatchCommand] plays its role.
 */
@Tag("integration")
class ModuleApiConsumerContractTest {
    /** Written as a use case would be (TPL-04): `-api` types only; the state change is a lambda. */
    private class FakeDespatchCommand(
        private val tenants: TenantContext,
        private val tx: TransactionRunner,
        private val outbox: OutboxWriter,
        private val saveState: (UUID) -> Unit,
    ) {
        // INV-T-3999 removes this call
        @Suppress("DEPRECATION")
        fun despatch(
            context: EventContext,
            failAfterEmit: Boolean,
        ) = tenants.bind(context.tenant) {
            tx.inTransaction {
                saveState(checkNotNull(context.docUuid).value)
                outbox.append(
                    listOf(OutboundEvent(TestCatalogue.SUBMISSION_DESPATCHED, """{"submissionId":"s-1"}""")),
                    context,
                )
                if (failAfterEmit) error("forced rollback")
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a failed command leaves no outbox row and a committed one leaves exactly its rows`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.submission
            val command =
                FakeDespatchCommand(kit.tenants, kit.boundary, kit.writer) { doc ->
                    kit.boundary
                        .require()
                        .prepareStatement(
                            "insert into submission.submission_state values (?, 'DESPATCHED')",
                        ).use {
                            it.setObject(1, doc)
                            it.executeUpdate()
                        }
                }
            val tenant = UUID.randomUUID()
            val rolledBack = UUID.randomUUID()
            val committed = UUID.randomUUID()
            val key = rolledBack.toString()

            assertThrows(IllegalStateException::class.java) {
                command.despatch(kit.docContext(tenant, rolledBack), failAfterEmit = true)
            }
            command.despatch(kit.docContext(tenant, committed), failAfterEmit = false)

            assertEquals(0, harness.rows("submission.submission_state", "doc_uuid", rolledBack))
            assertEquals(0, harness.rows("submission.outbox", "subject", key))
            assertEquals(0, harness.rows("submission.outbox_sequence", "aggregate_key", key))
            assertEquals(1, harness.rows("submission.submission_state", "doc_uuid", committed))
            assertEquals(1, harness.rows("submission.outbox", "subject", committed.toString()))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `every published event carries the full envelope and only its catalogued service emits it`(image: String) {
        OutboxHarness(image).use { harness ->
            val tenant = UUID.randomUUID()
            val doc = UUID.randomUUID()
            val emissions =
                listOf(
                    harness.canonical to TestCatalogue.CANONICAL_VERSION_CREATED,
                    harness.payload to TestCatalogue.PAYLOAD_READY,
                    harness.submission to TestCatalogue.SUBMISSION_DESPATCHED,
                    harness.submission to TestCatalogue.SUBMISSION_ACKNOWLEDGED,
                    // A platform event: every service may emit it.
                    harness.reconciliation to TestCatalogue.AUDIT_ENTRY_RECORDED,
                )
            emissions.forEach { (kit, type) ->
                kit.inTx(tenant) {
                    kit.writer.append(
                        listOf(OutboundEvent(type, """{"version":1}""")),
                        kit.docContext(tenant, doc),
                    )
                }
                harness.drain(kit.relay)
            }

            emissions.forEach { (kit, type) ->
                val topic = checkNotNull(harness.catalogue.find(type)).topic
                val event = harness.logOf(topic).single()
                assertEquals(type, event.type)
                assertEquals("/gst/${kit.service}", event.source)
                assertEquals(tenant, event.tenant.value)
                assertEquals("entity-1", event.entity.value)
                assertEquals(doc, event.docUuid?.value)
                assertNotNull(event.transactionDate)
                assertEquals("${kit.service}-1", event.instanceId)
                assertTrue(event.correlation.value.isNotBlank() && event.causation.value.isNotBlank())
                assertTrue(UUID.fromString(event.id.value).version() == 7)
                assertNotNull(event.docSequence)
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a service cannot emit a type catalogued to another and nothing is written`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.submission
            val context = kit.docContext(UUID.randomUUID(), UUID.randomUUID())
            val rejected =
                assertThrows(EmissionNotPermitted::class.java) {
                    kit.inTx(context.tenant.value) {
                        kit.writer.append(
                            listOf(
                                OutboundEvent(TestCatalogue.SUBMISSION_DESPATCHED, "{}"),
                                OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}"), // payload-svc's type
                            ),
                            context,
                        )
                    }
                }
            assertEquals("submission-svc", rejected.service)
            assertThrows(EmissionNotPermitted::class.java) {
                kit.inTx(context.tenant.value) {
                    kit.writer.append(
                        listOf(OutboundEvent("com.dynokore.gst.submission.Invented.v1", "{}")),
                        context,
                    )
                }
            }
            assertEquals(0, harness.count("select count(*) from submission.outbox"))
            assertNull(harness.catalogue.find("com.dynokore.gst.submission.Invented.v1"))
        }
    }
}
