package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.persistence.JooqCompletedRuns
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunItems
import com.dynokore.gst.reconciliation.domain.run.ReconcilingItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** INV-AC-24463 (INV-T-24464): the append-only, tenant-isolated, ordered reconciling items of a run. */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RunItemsDbTest : RunStoreDbFixture() {
    private val itemC = ReconcilingItem("2", "doc-1", 1, 1, BigDecimal("7.5"), listOf("EV-3"))
    private val itemA = ReconcilingItem("1", "doc-2", 1, 2, BigDecimal("3.00"), listOf("EV-1", "EV-2"))
    private val itemB = ReconcilingItem("1", "doc-2", 2, 1, BigDecimal("4"), listOf("EV-4"))
    private val ordered = listOf(itemA.at(), itemB.at(), itemC.at())

    private fun ReconcilingItem.at() = copy(amount = amount.setScale(2))

    private fun withItems(items: List<ReconcilingItem>) = outcome().copy(reconcilingItems = items)

    private fun items(
        tenant: UUID,
        runId: String,
    ): List<ReconcilingItem> =
        asTenant(tenant) {
            session.runner.inTransaction { JooqRunItems(session.dsl()).items(tenant.toString(), runId) }
        }

    private fun recordWithId(
        tenant: UUID,
        runId: UUID,
        outcome: com.dynokore.gst.reconciliation.domain.run.RunOutcome.Completed,
    ) = asTenant(tenant) {
        session.runner.inTransaction {
            JooqCompletedRuns(session.dsl()) { runId }.record(
                tenant.toString(),
                ENTITY,
                PERIOD,
                outcome,
                Instant.parse("2026-04-28T09:10:00Z"),
            )
        }
    }

    @Test
    fun `INV-AC-24463 the read returns the three items in stored order at scale 2 with evidence`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z", withItems(listOf(itemC, itemB, itemA)))
        val read = items(t1, run.runId)
        read shouldBe ordered
        read.map { it.amount.scale() } shouldBe listOf(2, 2, 2)
        read.map { it.evidenceRefs } shouldBe listOf(listOf("EV-1", "EV-2"), listOf("EV-4"), listOf("EV-3"))
    }

    @Test
    fun `INV-AC-24463 the read for another tenant returns none and one with a foreign run id returns none`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z", withItems(listOf(itemA, itemB, itemC)))
        items(t2, run.runId).shouldBeEmpty()
        items(t1, UUID.randomUUID().toString()).shouldBeEmpty()
        // The tenant predicate is the reader's own: a session bound to T2 asking with T1 named reads nothing either.
        asTenant(t2) {
            session.runner.inTransaction { JooqRunItems(session.dsl()).items(t1.toString(), run.runId) }
        }.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-24463 an update and a delete of an item row are refused by the database with 42501`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z", withItems(listOf(itemA)))
        val update = "update reconciliation.reconciliation_run_item set box = '9' where run_id = '${run.runId}'"
        val delete = "delete from reconciliation.reconciliation_run_item where run_id = '${run.runId}'"
        runtimeSqlState(t1, update) shouldBe "42501"
        runtimeSqlState(t1, delete) shouldBe "42501"
        ReconciliationDb.superuser().use { c ->
            c.createStatement().use { st ->
                shouldThrow<java.sql.SQLException> { st.executeUpdate(update) }.sqlState shouldBe "42501"
                shouldThrow<java.sql.SQLException> { st.executeUpdate(delete) }.sqlState shouldBe "42501"
            }
        }
        items(t1, run.runId) shouldBe listOf(itemA.at())
    }

    @Test
    fun `INV-AC-24463 a second record of the same run id stores no second set`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val runId = UUID.randomUUID()
        recordWithId(t1, runId, withItems(listOf(itemA, itemB, itemC)))
        shouldThrow<Exception> { recordWithId(t1, runId, withItems(listOf(itemA, itemB, itemC))) }
        count("reconciliation_run_item", t1) shouldBe 3L
        count("reconciliation_run", t1) shouldBe 1L
        items(t1, runId.toString()) shouldBe ordered
    }

    @Test
    fun `INV-AC-24463 an item row without a run row is refused with 23503`() {
        val t1 = UUID.randomUUID()
        val sql =
            "insert into reconciliation.reconciliation_run_item (tenant_id, run_id, box, doc_uuid, version, " +
                "line_number, amount, evidence_refs) values ('$t1', '${UUID.randomUUID()}', '1', 'd', 1, 1, 1.00, '{e}')"
        runtimeSqlState(t1, sql) shouldBe "23503"
    }

    @Test
    fun `INV-AC-24463 a failed insert of the items leaves no run row and no item, one transaction`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        shouldThrow<Exception> { record(t1, "2026-04-28T09:10:00Z", withItems(listOf(itemA, itemB, itemA))) }
        count("reconciliation_run", t1) shouldBe 0L
        count("reconciliation_run_item", t1) shouldBe 0L
        count("reconciliation_run_box", t1) shouldBe 0L
    }

    @Test
    fun `INV-AC-24463 an item row with an empty evidence list is refused with 23514`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z", withItems(emptyList()))
        val sql =
            "insert into reconciliation.reconciliation_run_item (tenant_id, run_id, box, doc_uuid, version, " +
                "line_number, amount, evidence_refs) values ('$t1', '${run.runId}', '1', 'd', 1, 1, 1.00, '{}')"
        runtimeSqlState(t1, sql) shouldBe "23514"
        count("reconciliation_run_item", t1) shouldBe 0L
    }

    @Test
    fun `INV-AC-24463 a run with no reconciling items stores none and reads none`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z", withItems(emptyList()))
        items(t1, run.runId).shouldBeEmpty()
    }
}
