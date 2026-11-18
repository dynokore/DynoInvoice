package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqPrecedingStates
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The preceding-document state read against `submission` as `submission_runtime` (INV-AC-24516, INV-REQ-709). */
class PrecedingStatesDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val reader = JooqPrecedingStates(dsl)
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val suffix = UUID.randomUUID().toString()
    private val p1 = "P1-$suffix"
    private val p2 = "P2-$suffix"
    private val p3 = "P3-$suffix"
    private val p4 = "P4-$suffix"

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            listOf(t1, t2).forEach { t ->
                c.createStatement().use {
                    it.execute("delete from submission.submission where tenant_id = '$t'")
                    it.execute("delete from submission.obligation_register_entry where tenant_id = '$t'")
                }
            }
        }
    }

    private fun entry(
        tenant: UUID,
        doc: String,
    ) = SubmissionDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into submission.obligation_register_entry " +
                    "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind) " +
                    "values (?, ?, 'E-001', 'P', ?, true, 'INVOICE')",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, doc)
                it.setObject(3, java.sql.Date.valueOf(LocalDate.of(2026, 3, 31)))
                it.executeUpdate()
            }
    }

    private fun version(
        tenant: UUID,
        doc: String,
        version: Int,
        state: String,
    ) = SubmissionDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                    "values (?, ?, ?, ?, 'E-001')",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, doc)
                it.setInt(3, version)
                it.setString(4, state)
                it.executeUpdate()
            }
    }

    private fun seed() {
        entry(t1, p1)
        version(t1, p1, 2, "QUEUED")
        version(t1, p1, 1, "SUPERSEDED")
        entry(t1, p2)
        version(t1, p3, 1, "READY")
        entry(t2, p4)
        version(t2, p4, 1, "ACKNOWLEDGED")
    }

    private fun read(
        tenant: UUID,
        doc: String,
    ): List<LifecycleState>? {
        SignedBindings.bind(connection, tenant)
        return try {
            reader.of(TenantId(tenant), doc).also { connection.commit() }
        } finally {
            connection.rollback()
        }
    }

    @Test
    fun `INV-AC-24516 the states of an entry are returned in version order`() {
        seed()
        read(t1, p1) shouldBe listOf(LifecycleState.SUPERSEDED, LifecycleState.QUEUED)
    }

    @Test
    fun `INV-AC-24516 an entry with no version row gives an empty list`() {
        seed()
        read(t1, p2) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24516 a document with version rows and no register entry gives null`() {
        seed()
        read(t1, p3) shouldBe null
    }

    @Test
    fun `INV-AC-24516 an unknown Doc UUID gives null`() {
        seed()
        read(t1, "unknown-$suffix") shouldBe null
    }

    @Test
    fun `INV-AC-24516 a row of another tenant is never read`() {
        seed()
        read(t2, p4) shouldBe listOf(LifecycleState.ACKNOWLEDGED)
        read(t2, p1) shouldBe null
        read(t1, p4) shouldBe null
    }

    @Test
    fun `INV-AC-24516 the query names the tenant id even when the row level security would admit the row`() {
        seed()
        // The bound tenant is T1 but the call names T2: the explicit predicate, not only RLS, decides.
        SignedBindings.bind(connection, t1)
        try {
            reader.of(TenantId(t2), p1) shouldBe null
            reader.of(TenantId(t1), p1) shouldBe listOf(LifecycleState.SUPERSEDED, LifecycleState.QUEUED)
        } finally {
            connection.rollback()
        }
    }

    @Test
    fun `INV-AC-24516 the state query carries its own tenant predicate without row level security`() {
        val p5 = "P5-$suffix"
        entry(t2, p5)
        version(t1, p5, 1, "ACKNOWLEDGED")
        SubmissionDb.superuser().use { c ->
            JooqPrecedingStates(DSL.using(c, SQLDialect.POSTGRES)).of(TenantId(t2), p5) shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-24516 a version row whose state is not a LifecycleState name makes the read throw`() {
        val mock =
            DSL.using(
                MockConnection { ctx ->
                    val create = DSL.using(SQLDialect.POSTGRES)
                    val rows =
                        if (ctx.sql().contains("obligation_register_entry")) {
                            create.newResult(DSL.field("x", Int::class.java)).also {
                                it.add(create.newRecord(DSL.field("x", Int::class.java)).values(1))
                            }
                        } else {
                            create.newResult(DSL.field("state", String::class.java)).also {
                                it.add(create.newRecord(DSL.field("state", String::class.java)).values("NOT_A_STATE"))
                            }
                        }
                    arrayOf(MockResult(rows.size, rows))
                },
                SQLDialect.POSTGRES,
            )
        shouldThrow<IllegalStateException> { JooqPrecedingStates(mock).of(TenantId(t1), p1) }
    }
}
