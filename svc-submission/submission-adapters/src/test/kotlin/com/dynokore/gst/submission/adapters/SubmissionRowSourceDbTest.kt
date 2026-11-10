package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionRowSource
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The list row reader against the test database as `submission_runtime` with the tenant bound (INV-AC-16324). */
class SubmissionRowSourceDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val statements = AtomicInteger()
    private val boundValues = mutableListOf<Any?>()
    private val countingConnection: Connection =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "prepareStatement" || method.name == "createStatement") {
                statements.incrementAndGet()
                boundValues.add(args?.firstOrNull())
            }
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
    private val countingDsl = DSL.using(countingConnection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return block().also { connection.commit() }
            }
        }

    private val reader = JooqSubmissionRowSource(countingDsl, runner)
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val version = Submission.SUBMISSION_
    private val due = LocalDate.of(2026, 11, 30)
    private val txDate = LocalDate.of(2026, 10, 15)
    private val submittedAt = Instant.parse("2026-11-01T02:03:04Z")
    private val acknowledgedAt = Instant.parse("2026-11-01T02:05:06Z")

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return runner.inTransaction(block)
        } finally {
            bound.remove()
        }
    }

    private fun read(
        tenant: UUID,
        entity: String,
        period: String,
    ): SourceRead {
        bound.set(tenant)
        try {
            return reader.rows(TenantId(tenant), entity, period)
        } finally {
            bound.remove()
        }
    }

    private fun seedEntry(
        tenant: UUID,
        doc: String,
        entity: String,
        period: String,
        withFacts: Boolean = true,
    ) = asTenant(tenant) {
        dsl
            .insertInto(entry)
            .set(entry.TENANT_ID, tenant)
            .set(entry.DOC_UUID, doc)
            .set(entry.ENTITY_REF, entity)
            .set(entry.PERIOD_REF, period)
            .set(entry.DUE_DATE, due)
            .set(entry.IN_SCOPE, true)
            .set(entry.KIND, "INVOICE")
            .set(if (withFacts) facts() else emptyMap<Field<*>, Any?>())
            .execute()
    }

    private fun facts(): Map<Field<*>, Any?> =
        mapOf(
            entry.DOCUMENT_NUMBER to "N-d1",
            entry.DOCUMENT_TYPE_CODE to "380",
            entry.DIRECTION to "SALE",
            entry.COUNTERPARTY_NAME to "Acme Pte Ltd",
            entry.COUNTERPARTY_UEN to "201912345A",
            entry.TAXABLE_AMOUNT to BigDecimal("100.00"),
            entry.GST_AMOUNT to BigDecimal("9.00"),
            entry.CATEGORY_CODES to arrayOf("SR", "ZR"),
        )

    private class Extras(
        val entity: String,
        val type: String? = "TYPE_1A",
        val ackAt: Instant? = null,
        val ackId: String? = null,
    )

    private fun seedVersion(
        tenant: UUID,
        doc: String,
        number: Int,
        state: LifecycleState,
        extras: Extras,
    ) = asTenant(tenant) {
        dsl
            .insertInto(version)
            .set(version.TENANT_ID, tenant)
            .set(version.DOC_UUID, doc)
            .set(version.VERSION, number)
            .set(version.STATE, state.name)
            .set(version.ENTITY_REF, extras.entity)
            .set(version.TRANSACTION_DATE, txDate)
            .set(version.SUBMISSION_TYPE, extras.type)
            .set(version.SUBMITTED_AT, submittedAt.atOffset(ZoneOffset.UTC))
            .set(version.ACKNOWLEDGED_AT, extras.ackAt?.atOffset(ZoneOffset.UTC))
            .set(version.ACKNOWLEDGEMENT_ID, extras.ackId)
            .execute()
    }

    private fun assertFactsOf(it: SubmissionRow) {
        it.documentNumber shouldBe "N-d1"
        it.documentTypeCode shouldBe "380"
        it.counterpartyName shouldBe "Acme Pte Ltd"
        it.counterpartyUen shouldBe "201912345A"
        it.taxableAmount shouldBe BigDecimal("100.00")
        it.gstAmount shouldBe BigDecimal("9.00")
        it.categoryCodes shouldBe setOf("SR", "ZR")
        it.dueDate shouldBe due
        it.transactionDate shouldBe txDate
        it.submittedAt shouldBe submittedAt
    }

    private fun ids() = UUID.randomUUID().toString().take(8)

    @Test
    fun `INV-AC-16324 the period read gives both versions with facts and counts the rows without facts`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val s = ids()
        val e = "e-$s"
        val p = "p-$s"
        seedEntry(a, "d1-$s", e, p)
        seedVersion(a, "d1-$s", 1, LifecycleState.SUPERSEDED, Extras(e))
        seedVersion(a, "d1-$s", 2, LifecycleState.ACKNOWLEDGED, Extras(e, ackAt = acknowledgedAt, ackId = "ACK-1"))
        seedEntry(a, "nofacts-$s", e, p, withFacts = false)
        seedVersion(a, "nofacts-$s", 1, LifecycleState.READY, Extras(e))
        seedEntry(a, "other-entity-$s", "x-$s", p)
        seedVersion(a, "other-entity-$s", 1, LifecycleState.READY, Extras("x-$s"))
        seedEntry(a, "other-period-$s", e, "y-$s")
        seedVersion(a, "other-period-$s", 1, LifecycleState.READY, Extras(e))
        seedEntry(b, "b1-$s", e, p)
        seedVersion(b, "b1-$s", 1, LifecycleState.READY, Extras(e))

        val read = read(a, e, p)
        val rows = read.rows.sortedBy { it.version }
        rows.map { it.docUuid to it.version } shouldBe listOf("d1-$s" to 1, "d1-$s" to 2)
        rows.map { it.superseded } shouldBe listOf(true, false)
        rows.forEach { assertFactsOf(it) }
        rows[0].acknowledgedAt shouldBe null
        rows[0].acknowledgementId shouldBe null
        rows[1].acknowledgedAt shouldBe acknowledgedAt
        rows[1].acknowledgementId shouldBe "ACK-1"
        read.withoutFacts shouldBe 1
        read(b, e, p).rows.map { it.docUuid } shouldBe listOf("b1-$s")
        read(b, e, p).withoutFacts shouldBe 0
    }

    @Test
    fun `INV-AC-16324 a version row without a type or with an unpaired acknowledgement is counted and not returned`() {
        val a = UUID.randomUUID()
        val s = ids()
        val e = "e-$s"
        val p = "p-$s"
        listOf("nt", "unpaired", "bare").forEach { seedEntry(a, "$it-$s", e, p) }
        seedVersion(a, "nt-$s", 1, LifecycleState.READY, Extras(e, type = null))
        seedVersion(a, "unpaired-$s", 1, LifecycleState.ACKNOWLEDGED, Extras(e, ackId = "ACK-9"))
        seedVersion(a, "bare-$s", 1, LifecycleState.SENT, Extras(e))
        val read = read(a, e, p)
        read.withoutFacts shouldBe 2
        read.rows.map { it.docUuid } shouldBe listOf("bare-$s")
        read.rows[0].acknowledgedAt shouldBe null
        read.rows[0].acknowledgementId shouldBe null
    }

    @Test
    fun `INV-AC-16324 reader_applies_no_filter returns one row per version of every state`() {
        val a = UUID.randomUUID()
        val s = ids()
        val e = "e-$s"
        val p = "p-$s"
        val states = LifecycleState.entries
        states.forEachIndexed { i, state ->
            seedEntry(a, "d$i-$s", e, p)
            seedVersion(a, "d$i-$s", 1, state, Extras(e))
        }
        val read = read(a, e, p)
        read.rows.size shouldBe states.size
        read.rows.map { it.state }.toSet() shouldBe states.toSet()
        read.withoutFacts shouldBe 0
    }

    @Test
    fun `INV-AC-16324 a read of 50 documents is one SQL statement`() {
        val a = UUID.randomUUID()
        val s = ids()
        val e = "e-$s"
        val p = "p-$s"
        repeat(50) {
            seedEntry(a, "d$it-$s", e, p)
            seedVersion(a, "d$it-$s", 1, LifecycleState.READY, Extras(e))
        }
        statements.set(0)
        read(a, e, p).rows.size shouldBe 50
        statements.get() shouldBe 1
    }

    @Test
    fun `INV-AC-16324 the statement carries the tenant condition itself as well as the row level security`() {
        val a = UUID.randomUUID()
        val s = ids()
        seedEntry(a, "d-$s", "e-$s", "p-$s")
        seedVersion(a, "d-$s", 1, LifecycleState.READY, Extras("e-$s"))
        boundValues.clear()
        read(a, "e-$s", "p-$s").rows.size shouldBe 1
        boundValues.size shouldBe 1
        Regex("\"tenant_id\" = (cast\\()?\\?").containsMatchIn(boundValues.single() as String) shouldBe true
        (boundValues.single() as String).contains("where") shouldBe true
    }
}
