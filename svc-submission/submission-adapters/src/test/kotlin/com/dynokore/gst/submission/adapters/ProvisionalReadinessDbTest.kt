package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqProvisionalEntryStore
import com.dynokore.gst.submission.adapters.persistence.JooqReadinessEntrySource
import com.dynokore.gst.submission.adapters.persistence.JooqRegisterEntryStore
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.application.register.ProvisionalEntry
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.ReadinessCounter
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.gst.submission.domain.readiness.ReadinessResult
import com.dynokore.gst.submission.domain.register.DocumentDirection
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Provisional rows through the real `JooqReadinessEntrySource` and `ReadinessCounter.evaluate` (INV-AC-1778, 1691). */
class ProvisionalReadinessDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return block().also { connection.commit() }
            }
        }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(checkNotNull(bound.get()))

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val store = JooqProvisionalEntryStore(dsl, runner)
    private val register = JooqRegisterEntryStore(dsl, runner)
    private val source =
        JooqReadinessEntrySource(
            dsl,
            tenants,
            ReleasedOverrideChecks { _, _ -> emptySet() },
            HoldingChecks { _, _, _ -> null },
        )
    private val tenant = UUID.randomUUID()
    private val entity = "E-${UUID.randomUUID()}".take(14)
    private val period = "2026-06"
    private val day = LocalDate.of(2026, 6, 20)

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        id: UUID,
        block: () -> T,
    ): T {
        bound.set(id)
        try {
            return block()
        } finally {
            bound.remove()
        }
    }

    private fun held(
        doc: String,
        reason: String?,
        docsequence: Long = 1,
        entityRef: String = entity,
    ) = ProvisionalEntry(
        doc,
        entityRef,
        period,
        DocumentDirection.SALE,
        day,
        day,
        reason,
        docsequence,
        Instant.parse("2026-11-12T08:00:00Z"),
    )

    private fun place(
        entry: ProvisionalEntry,
        id: UUID = tenant,
    ) = asTenant(id) { store.place(TenantId(id), entry) }

    private fun read(
        id: UUID = tenant,
        entityRef: String = entity,
    ): List<ReadinessEntry> = asTenant(id) { runner.inTransaction { checkNotNull(source.entries(entityRef, period)) } }

    private fun blocked(entries: List<ReadinessEntry>): ReadinessResult.Blocked =
        ReadinessCounter.evaluate(entries, null, true).shouldBeInstanceOf<ReadinessResult.Blocked>()

    private fun derived(doc: String) =
        RegisterEntry(
            docUuid = doc,
            entityRef = entity,
            periodRef = period,
            dueDate = LocalDate.of(2026, 11, 30),
            inScope = true,
            kind = RowKind.INVOICE,
            facts =
                ListFacts(
                    "INV-1",
                    "380",
                    Direction.SALE,
                    "Acme Pte Ltd",
                    "201912345A",
                    BigDecimal("100.00"),
                    BigDecimal("9.00"),
                    setOf("SR"),
                ),
            sourceVersion = 1,
            transactionDate = day,
        )

    @Test
    fun `INV-AC-1778 a provisional entry is counted once before and after the derived entry replaces it`() {
        val doc = UUID.randomUUID().toString()
        place(held(doc, "ING-016"))
        blocked(read()).counts.unsubmitted shouldBe 1
        asTenant(tenant) { register.upsert(TenantId(tenant), derived(doc)) }
        val after = read()
        after.map { it.docUuid } shouldBe listOf(doc)
        blocked(after).counts.unsubmitted shouldBe 1
        asTenant(tenant) {
            runner.inTransaction {
                dsl.fetch("select 1 from submission.provisional_register_entry where doc_uuid = ?", doc).size
            }
        } shouldBe 0
    }

    @Test
    fun `INV-AC-1691 a held document is unsubmitted with its reason, and INGESTED without it after the release`() {
        val doc = UUID.randomUUID().toString()
        place(held(doc, "MDM-001"))
        val before = blocked(read())
        before.counts.unsubmitted shouldBe 1
        before.residual.single().reasonCode shouldBe "MDM-001"
        read().single().versions shouldBe listOf(LifecycleState.HELD)
        asTenant(tenant) { store.release(TenantId(tenant), doc, 2) } shouldBe true
        val after = read()
        after.single().versions shouldBe listOf(LifecycleState.INGESTED)
        after.single().reasonCode shouldBe null
        blocked(after).counts.unsubmitted shouldBe 1
    }

    @Test
    fun `the anti-join hides a provisional row whose docUuid has a register row`() {
        val doc = UUID.randomUUID().toString()
        place(held(doc, "ING-016"))
        // Insert the register row directly so the provisional row survives (the store would delete it).
        asTenant(tenant) {
            runner.inTransaction {
                dsl.execute(
                    "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                        "due_date, in_scope, kind, transaction_date) values (?, ?, ?, ?, ?, true, 'INVOICE', ?)",
                    tenant,
                    doc,
                    entity,
                    period,
                    LocalDate.of(2026, 11, 30),
                    day,
                )
            }
        }
        read().map { it.docUuid } shouldBe listOf(doc)
        read().single().versions shouldBe listOf(LifecycleState.QUEUED)
    }

    @Test
    fun `a second tenant's provisional row is never read`() {
        val other = UUID.randomUUID()
        place(held(UUID.randomUUID().toString(), "ING-016"), other)
        read() shouldBe emptyList()
        read(other).size shouldBe 1
    }
}
