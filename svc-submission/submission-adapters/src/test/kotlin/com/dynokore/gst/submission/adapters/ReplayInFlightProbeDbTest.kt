package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqReplayInFlightProbe
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.UUID

/** The in-flight probe of the replay preview against the test database as `submission_runtime` (INV-REQ-1012). */
class ReplayInFlightProbeDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return try {
                    block().also { connection.commit() }
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                }
            }
        }
    private val probe = JooqReplayInFlightProbe(dsl, runner)
    private val sub = Submission.SUBMISSION_
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val s = UUID.randomUUID().toString().take(8)
    private var doc = "doc-$s"

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

    private fun seed(
        tenant: UUID,
        version: Int,
        state: LifecycleState,
    ) = asTenant(tenant) {
        dsl
            .insertInto(sub)
            .set(sub.TENANT_ID, tenant)
            .set(sub.DOC_UUID, doc)
            .set(sub.VERSION, version)
            .set(sub.STATE, state.name)
            .set(sub.ENTITY_REF, "e-$s")
            .execute()
    }

    private fun ask(
        tenant: UUID,
        version: Int,
    ): Boolean = asTenant(tenant) { probe.hasOtherInFlight(TenantId(tenant), doc, version) }

    private fun otherVersionIn(state: LifecycleState): Boolean {
        seed(tenantA, 1, LifecycleState.DEAD_LETTERED)
        seed(tenantA, 2, state)
        return ask(tenantA, 1)
    }

    @Test
    fun `INV-AC-25828 another version in QUEUED gives true`() {
        otherVersionIn(LifecycleState.QUEUED) shouldBe true
    }

    @Test
    fun `INV-AC-25828 another version in SENT gives true`() {
        otherVersionIn(LifecycleState.SENT) shouldBe true
    }

    @Test
    fun `INV-AC-25828 another version in SENT_UNCONFIRMED gives true`() {
        otherVersionIn(LifecycleState.SENT_UNCONFIRMED) shouldBe true
    }

    @Test
    fun `INV-AC-25828 another version in RETRY gives true`() {
        otherVersionIn(LifecycleState.RETRY) shouldBe true
    }

    @Test
    fun `INV-AC-25828 the in-flight set of the test is the four states and the others are listed`() {
        InFlight.NAMES shouldBe setOf("QUEUED", "SENT", "SENT_UNCONFIRMED", "RETRY")
        notInFlight().size shouldBe LifecycleState.entries.size - 4
    }

    private fun notInFlight() = LifecycleState.entries.filter { it.name !in InFlight.NAMES }

    /** One case per `LifecycleState` value outside `InFlight.NAMES`: another version in it gives false. */
    @TestFactory
    fun `INV-AC-25828 another version in a state outside the in-flight set gives false`(): List<DynamicTest> =
        notInFlight().map { state ->
            DynamicTest.dynamicTest("INV-AC-25828 another version in $state gives false") {
                doc = "doc-$s-$state"
                seed(tenantA, 1, LifecycleState.DEAD_LETTERED)
                seed(tenantA, 2, state)
                ask(tenantA, 1) shouldBe false
            }
        }

    @Test
    fun `INV-AC-25828 the named version itself in flight and no other version gives false`() {
        seed(tenantA, 1, LifecycleState.QUEUED)
        ask(tenantA, 1) shouldBe false
    }

    @Test
    fun `INV-AC-25828 another tenant's in-flight version of the same Doc UUID gives false`() {
        seed(tenantA, 1, LifecycleState.DEAD_LETTERED)
        seed(tenantB, 2, LifecycleState.QUEUED)
        ask(tenantA, 1) shouldBe false
        ask(tenantB, 1) shouldBe true
    }

    @Test
    fun `INV-AC-25828 no row at all gives false`() {
        ask(tenantA, 1) shouldBe false
    }

    /** The statement text the adapter issues, recorded by a mock JDBC connection that answers "one row". */
    private fun issuedBy(tenant: UUID): List<String> {
        val recorded = mutableListOf<String>()
        val mock =
            DSL.using(
                MockConnection { ctx ->
                    recorded.add(ctx.sql())
                    val result = DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("exists", Boolean::class.java))
                    result.add(
                        DSL.using(SQLDialect.POSTGRES).newRecord(DSL.field("exists", Boolean::class.java)).also {
                            it.set(DSL.field("exists", Boolean::class.java), true)
                        },
                    )
                    arrayOf(MockResult(1, result))
                },
                SQLDialect.POSTGRES,
            )
        val noTransaction =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        JooqReplayInFlightProbe(mock, noTransaction).hasOtherInFlight(TenantId(tenant), doc, 1)
        return recorded
    }

    @Test
    fun `INV-AC-25828 the probe issues one read-only statement with no lock and no write`() {
        val statements = issuedBy(tenantA)
        statements.size shouldBe 1
        val text = statements.single().lowercase()
        // The tenant is in the WHERE clause as well as the row level security (CS-19).
        text.replace("\"", "").contains("tenant_id = cast(? as uuid)") shouldBe true
        listOf("for update", "for share", "insert", "update", "delete").filter { text.contains(it) } shouldBe
            emptyList()
    }
}
