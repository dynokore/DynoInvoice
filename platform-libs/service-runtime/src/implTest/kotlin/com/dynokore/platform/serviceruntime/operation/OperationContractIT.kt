package com.dynokore.platform.serviceruntime.operation

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.serviceruntime.jobs.JobsHarness
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.AUDIT
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SCHEMA
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.START
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_A
import com.dynokore.platform.serviceruntime.jobs.MutableClock
import com.dynokore.platform.tenancyjooq.TableClass
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-REQ-957 and INV-REQ-1010 on fixture operations through the [TransactionalOperationRunner] over PostgreSQL:
 * the role and scope check refuses before any state is read, the precondition is checked against the stored
 * state, the repeat key returns the first result with no second effect or audit, the audit entry commits in the
 * same transaction as the change (a failed audit leaves no change), and every declared operation has exactly one
 * declared reversibility.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationContractIT {
    // the fixture table is created with the service at container start (INV-Q-5119); it is a tenant table
    private val h =
        JobsHarness(
            "postgres:17",
            """
            create table $SCHEMA.fixture_doc (id text primary key, tenant_id uuid not null, state text not null, approved_by_key text);
            grant select, insert, update on $SCHEMA.fixture_doc to ${OutboxPostgresFixture.APP};
            """.trimIndent(),
            mapOf("$SCHEMA.fixture_doc" to TableClass.TENANT),
        )
    private val replica = h.replica("svc-1", MutableClock(START))
    private val runner = TransactionalOperationRunner(replica.tx, replica.dsl, OutboxAuditWriter(replica.outbox))
    private val fixtureDoc = DSL.table(DSL.name(SCHEMA, "fixture_doc"))
    private val docId = DSL.field(DSL.name("id"), SQLDataType.CLOB)
    private val docTenant = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
    private val docState = DSL.field(DSL.name("state"), SQLDataType.CLOB)
    private val docKey = DSL.field(DSL.name("approved_by_key"), SQLDataType.CLOB)

    @AfterAll
    fun close() = h.close()

    data class Approve(
        val doc: String,
        val key: UUID,
        val tenantScoped: Boolean = true,
    )

    data class Doc(
        val state: String?,
        val approvedByKey: String?,
    )

    /** A fixture operation: an approver approves a DRAFT document once per key; audited and undoable. */
    private inner class ApproveDoc(
        override val audited: Boolean = true,
        private val auditOverride: OperationAudit? = null,
        private val lockRow: Boolean = true,
        private val afterLoad: () -> Unit = {},
    ) : DeclaredOperation<Approve, Doc, String> {
        override val operationId = "approveFixtureDoc"
        override val reversibility = Reversibility.UNDOABLE
        var loads = 0

        override fun permits(
            actor: String,
            command: Approve,
        ) = actor == "approver"

        override fun repeatKey(command: Approve) = "${command.doc}:${command.key}"

        override fun load(command: Approve): Doc {
            loads++
            // runs inside the runner's transaction: read through its DSLContext, locking the row
            val query =
                replica.dsl
                    .select(docState, docKey)
                    .from(fixtureDoc)
                    .where(docId.eq(command.doc))
            val rows = (if (lockRow) query.forUpdate() else query).fetch { Doc(it.get(docState), it.get(docKey)) }
            afterLoad()
            return rows.singleOrNull() ?: Doc(null, null)
        }

        override fun priorResult(
            command: Approve,
            state: Doc,
        ) = if (state.approvedByKey == repeatKey(command)) "APPROVED" else null

        override fun unmetPrecondition(
            command: Approve,
            state: Doc,
        ) = when (state.state) {
            null -> "DOC-404"
            "DRAFT" -> null
            else -> "DOC-STATE"
        }

        override fun perform(
            command: Approve,
            state: Doc,
        ): String {
            replica.dsl
                .update(fixtureDoc)
                .set(docState, "APPROVED")
                .set(docKey, repeatKey(command))
                .where(docId.eq(command.doc))
                .execute()
            return "APPROVED"
        }

        override fun audit(
            command: Approve,
            state: Doc,
            result: String,
        ) = if (!audited) {
            null
        } else {
            auditOverride ?: OperationAudit(
                "fixture/${command.doc}",
                "approve",
                """{"state":"${state.state}"}""",
                EventContext(
                    TENANT_A,
                    EntityRef("E-001"),
                    null,
                    null,
                    "fixture/${command.doc}",
                    null,
                    CorrelationId("c-${command.key}"),
                    CausationId("k-${command.key}"),
                ),
            )
        }
    }

    private fun insert(doc: String) =
        replica.asTenant(TENANT_A) {
            replica.tx.inTransaction {
                replica.dsl
                    .insertInto(fixtureDoc)
                    .columns(docId, docTenant, docState)
                    .values(doc, TENANT_A.value, "DRAFT")
                    .execute()
            }
        }

    private fun state(doc: String) = h.rows("$SCHEMA.fixture_doc", "id" to doc).single()["state"]

    private fun audits(doc: String) = h.events(AUDIT, "fixture/$doc")

    @Test
    fun `an actor without the role is refused before any state is read`() {
        insert("d-role")
        val op = ApproveDoc()
        replica.asTenant(TENANT_A) { runner.run(op, "viewer", Approve("d-role", UUID.randomUUID())) } shouldBe
            OperationOutcome.NotPermitted
        op.loads shouldBe 0
        state("d-role") shouldBe "DRAFT"
    }

    @Test
    fun `the operation is performed and audited once, and a repeat with its key has no second effect`() {
        insert("d-once")
        val op = ApproveDoc()
        val key = UUID.randomUUID()
        replica.asTenant(TENANT_A) { runner.run(op, "approver", Approve("d-once", key)) } shouldBe
            OperationOutcome.Performed("APPROVED")
        replica.asTenant(TENANT_A) { runner.run(op, "approver", Approve("d-once", key)) } shouldBe
            OperationOutcome.Repeated("APPROVED")
        state("d-once") shouldBe "APPROVED"
        audits("d-once") shouldHaveSize 1
    }

    @Test
    fun `two concurrent commands with the same key run one after the other, with one effect and one audit`() {
        insert("d-race")
        // the operation takes no row lock; both commands wait here for the other to have loaded too (bounded), so
        // without the runner's repeat-key lock both would read "not yet approved" and both perform and audit
        val bothLoaded = CountDownLatch(2)
        val op =
            ApproveDoc(lockRow = false, afterLoad = {
                bothLoaded.countDown()
                bothLoaded.await(2, TimeUnit.SECONDS)
            })
        val key = UUID.randomUUID()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs =
                (1..2).map {
                    pool.submit<OperationOutcome<String>> {
                        start.await()
                        replica.asTenant(TENANT_A) { runner.run(op, "approver", Approve("d-race", key)) }
                    }
                }
            start.countDown()
            runs.map { it.get(30, TimeUnit.SECONDS) }.toSet() shouldBe
                setOf(OperationOutcome.Performed("APPROVED"), OperationOutcome.Repeated("APPROVED"))
        } finally {
            pool.shutdownNow()
        }
        audits("d-race") shouldHaveSize 1
    }

    @Test
    fun `an unmet precondition refuses against the stored state, not the caller's view`() {
        insert("d-pre")
        val op = ApproveDoc()
        replica.asTenant(TENANT_A) { runner.run(op, "approver", Approve("d-pre", UUID.randomUUID())) }
        replica.asTenant(TENANT_A) { runner.run(op, "approver", Approve("d-pre", UUID.randomUUID())) } shouldBe
            OperationOutcome.PreconditionUnmet("DOC-STATE")
        replica.asTenant(TENANT_A) { runner.run(op, "approver", Approve("d-none", UUID.randomUUID())) } shouldBe
            OperationOutcome.PreconditionUnmet("DOC-404")
        audits("d-pre") shouldHaveSize 1
    }

    @Test
    fun `a failed audit write leaves no change, the audit commits with the change`() {
        insert("d-audit")
        val failing =
            TransactionalOperationRunner(
                replica.tx,
                replica.dsl,
                object : AuditWriter by OutboxAuditWriter(replica.outbox) {
                    override fun record(
                        entry: AuditEntry,
                        context: EventContext,
                    ) = error("audit store down")
                },
            )
        assertThrows<IllegalStateException> {
            replica.asTenant(TENANT_A) { failing.run(ApproveDoc(), "approver", Approve("d-audit", UUID.randomUUID())) }
        }
        state("d-audit") shouldBe "DRAFT"
        audits("d-audit") shouldHaveSize 0
    }

    @Test
    fun `an audited operation that records no audit entry is refused and changes nothing`() {
        insert("d-noaudit")
        val op =
            object : DeclaredOperation<Approve, Doc, String> by ApproveDoc() {
                override fun audit(
                    command: Approve,
                    state: Doc,
                    result: String,
                ): OperationAudit? = null
            }
        assertThrows<IllegalStateException> {
            replica.asTenant(TENANT_A) { runner.run(op, "approver", Approve("d-noaudit", UUID.randomUUID())) }
        }
        state("d-noaudit") shouldBe "DRAFT"
    }

    @Test
    fun `every declared operation has one id and one reversibility`() {
        val registry = OperationRegistry()
        registry.declare(ApproveDoc())
        assertThrows<IllegalArgumentException> { registry.declare(ApproveDoc()) }
        registry.reversibilities() shouldBe mapOf("approveFixtureDoc" to Reversibility.UNDOABLE)
    }
}
