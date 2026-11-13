package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqPackMarginStore
import com.dynokore.gst.submission.application.pack.MarginUnit
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The pack margin store against the test database as `submission_runtime` (INV-AC-20786). */
class PackMarginStoreDbTest {
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

    private val store = JooqPackMarginStore(dsl, runner)
    private val days5 = PackMargin.of(5, "DAYS")
    private val days3 = PackMargin.of(3, "DAYS")

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return block()
        } finally {
            bound.remove()
        }
    }

    private fun stage(
        tenant: UUID,
        id: String,
        version: String,
        margin: PackMargin?,
    ) = asTenant(tenant) { store.stageCalendar(TenantId(tenant), id, version, margin) }

    private fun activate(
        tenant: UUID,
        id: String,
        version: String,
        date: String,
    ) = asTenant(tenant) { store.recordActivation(TenantId(tenant), id, version, LocalDate.parse(date)) }

    private fun activations(
        tenant: UUID,
        date: String,
    ) = asTenant(tenant) { store.calendarActivations(TenantId(tenant), LocalDate.parse(date)) }

    @Test
    fun `INV-AC-20786 a staged version and its activation are joined with the margin`() {
        val t = UUID.randomUUID()
        stage(t, "P-CAL", "1", days5) shouldBe true
        activate(t, "P-CAL", "1", "2026-01-01") shouldBe true
        val rows = activations(t, "2026-06-30")
        rows.map { Triple(it.packIdentifier, it.version, it.effectiveFrom) } shouldBe
            listOf(Triple("P-CAL", "1", LocalDate.of(2026, 1, 1)))
        rows.single().margin shouldBe days5
        activations(t, "2025-12-31") shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20786 an activation without a staged version is not returned and a version without margin has none`() {
        val t = UUID.randomUUID()
        activate(t, "P-CAL", "9", "2026-01-01")
        activations(t, "2026-06-30") shouldBe emptyList()
        stage(t, "P-CAL", "3", null)
        activate(t, "P-CAL", "3", "2027-01-01")
        activations(t, "2027-01-01").single().margin shouldBe null
    }

    @Test
    fun `INV-AC-20786 the join matches the identifier as well as the version`() {
        val t = UUID.randomUUID()
        stage(t, "P-A", "1", days5)
        stage(t, "P-B", "1", days3)
        activate(t, "P-A", "1", "2026-01-01")
        activate(t, "P-B", "1", "2026-02-01")
        activations(t, "2026-12-31").associate { it.packIdentifier to it.margin } shouldBe
            mapOf("P-A" to days5, "P-B" to days3)
    }

    @Test
    fun `INV-AC-20786 a second write of the same identifier and version changes nothing`() {
        val t = UUID.randomUUID()
        stage(t, "P-CAL", "1", days5) shouldBe true
        stage(t, "P-CAL", "1", days3) shouldBe false
        stage(t, "P-CAL", "1", null) shouldBe false
        activate(t, "P-CAL", "1", "2026-01-01") shouldBe true
        activate(t, "P-CAL", "1", "2026-09-01") shouldBe false
        val row = activations(t, "2026-12-31").single()
        row.margin shouldBe days5
        row.effectiveFrom shouldBe LocalDate.of(2026, 1, 1)
    }

    @Test
    fun `INV-AC-20786 hours margins are stored and read back`() {
        val t = UUID.randomUUID()
        stage(t, "P-CAL", "1", PackMargin.of(36, "HOURS"))
        activate(t, "P-CAL", "1", "2026-01-01")
        activations(t, "2026-01-01").single().margin?.unit shouldBe MarginUnit.HOURS
    }

    @Test
    fun `INV-AC-20786 rows of another tenant are never read nor changed`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        stage(a, "P-CAL", "1", days5)
        activate(a, "P-CAL", "1", "2026-01-01")
        activations(b, "2026-12-31") shouldBe emptyList()
        stage(b, "P-CAL", "1", days3) shouldBe true
        activate(b, "P-CAL", "1", "2026-03-01") shouldBe true
        activations(a, "2026-12-31").single().margin shouldBe days5
        activations(b, "2026-12-31").single().margin shouldBe days3
        // a statement under the b binding sees only b rows, whatever its own predicate
        asTenant(b) {
            SignedBindings.bind(connection, b)
            dsl.fetchCount(DSL.table(DSL.name("submission", "calendar_pack_version"))) shouldBe 1
            dsl.fetchCount(DSL.table(DSL.name("submission", "calendar_pack_activation"))) shouldBe 1
            connection.commit()
        }
    }

    @Test
    fun `INV-AC-20786 a statement without a bound tenant returns no row and cannot insert`() {
        val t = UUID.randomUUID()
        stage(t, "P-CAL", "1", days5)
        activate(t, "P-CAL", "1", "2026-01-01")
        for (table in listOf("calendar_pack_version", "calendar_pack_activation")) {
            dsl.fetchCount(DSL.table(DSL.name("submission", table))) shouldBe 0
            connection.rollback()
        }
        failsWith(
            "insert into submission.calendar_pack_version (tenant_id, pack_identifier, pack_version) " +
                "values ('${UUID.randomUUID()}', 'X', '1')",
        )
    }

    @Test
    fun `INV-AC-20786 the runtime role cannot update or delete either table`() {
        val t = UUID.randomUUID()
        stage(t, "P-CAL", "1", days5)
        activate(t, "P-CAL", "1", "2026-01-01")
        for (
        sql in
        listOf(
            "update submission.calendar_pack_version set margin_value = 1",
            "delete from submission.calendar_pack_version",
            "update submission.calendar_pack_activation set effective_from = date '2030-01-01'",
            "delete from submission.calendar_pack_activation",
        )
        ) {
            failsWith(sql, t)
        }
        activations(t, "2026-12-31").single().margin shouldBe days5
    }

    @Test
    fun `INV-AC-20786 the database refuses a margin out of range or with one column only`() {
        val t = UUID.randomUUID()
        for ((value, unit) in listOf(
            "8785" to "'DAYS'",
            "-1" to "'DAYS'",
            "5" to "'WEEKS'",
            "5" to "null",
            "null" to "'DAYS'",
        )) {
            failsWith("insert into submission.calendar_pack_version values ('$t', 'P', '1', $value, $unit)", t)
        }
    }

    /** The statement is refused by the database (permission, row level security or a check), never merely a no-op. */
    private fun failsWith(
        sql: String,
        tenant: UUID? = null,
    ) {
        if (tenant != null) SignedBindings.bind(connection, tenant)
        val refused = runCatching { connection.createStatement().use { it.executeUpdate(sql) } }.isFailure
        connection.rollback()
        refused shouldBe true
    }
}
