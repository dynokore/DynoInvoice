package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.submission.adapters.persistence.JooqReleasedOverrideChecks
import com.dynokore.gst.submission.application.gate.NewOverride
import com.dynokore.gst.submission.application.gate.OverrideStore
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** The override store and the released-checks read against the test database as `submission_runtime`. */
class OverrideStoreDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private var bound: UUID = UUID.randomUUID()
    private val created = mutableListOf<UUID>()

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(bound)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val store: OverrideStore = JooqOverrideStore(dsl, tenants)
    private val released: ReleasedOverrideChecks = JooqReleasedOverrideChecks(dsl, tenants)

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use { it.execute("delete from submission.filing_override where tenant_id = '$t'") }
            }
        }
    }

    private fun tenant() = UUID.randomUUID().also { created += it }

    private fun <T> inTx(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        bound = tenant
        return try {
            block().also { connection.commit() }
        } finally {
            connection.rollback()
        }
    }

    private fun pending(
        check: String,
        period: String = "P",
    ) = NewOverride("E", period, check, "reason", OverrideStatus.PENDING, "U1", null)

    private fun releasedRow(
        check: String,
        period: String = "P",
    ) = NewOverride("E", period, check, "reason", OverrideStatus.RELEASED, "U1", "U2")

    @Test
    fun `INV-AC-20556 insert then entries returns the rows with their user ids`() {
        val t = tenant()
        val a = inTx(t) { store.insert(pending("chk-1")) }
        val b = inTx(t) { store.insert(releasedRow("chk-2")) }
        inTx(t) { store.entries("E", "P") } shouldContainExactlyInAnyOrder listOf(a, b)
        a.requestedBy shouldBe "U1"
        b.releasedBy shouldBe "U2"
        inTx(t) { store.entries("E", "P2") } shouldBe emptyList()
        inTx(t) { store.entries("E2", "P") } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20556 findPending finds a PENDING row only`() {
        val t = tenant()
        val p = inTx(t) { store.insert(pending("chk-1")) }
        val r = inTx(t) { store.insert(releasedRow("chk-2")) }
        inTx(t) { store.findPending("E", "P", p.id) } shouldBe p
        inTx(t) { store.findPending("E", "P", r.id) } shouldBe null
        inTx(t) { store.findPending("E", "P2", p.id) } shouldBe null
    }

    @Test
    fun `INV-AC-20556 release changes one row once and a second release returns false`() {
        val t = tenant()
        val p = inTx(t) { store.insert(pending("chk-1")) }
        inTx(t) { store.release(p.id, "U2") } shouldBe true
        inTx(t) { store.release(p.id, "U3") } shouldBe false
        val row = inTx(t) { store.entries("E", "P") }.single()
        row.status shouldBe OverrideStatus.RELEASED
        row.releasedBy shouldBe "U2"
        inTx(t) { store.findPending("E", "P", p.id) } shouldBe null
    }

    @Test
    fun `INV-AC-20556 a RELEASED row cannot be stored without releasedBy`() {
        val t = tenant()
        shouldThrow<Exception> {
            inTx(t) {
                dsl.execute(
                    "insert into submission.filing_override (tenant_id, id, entity_ref, period_ref, check_id, " +
                        "reason, status, requested_by) values (?, 'x', 'E', 'P', 'chk-1', 'r', 'RELEASED', 'U1')",
                    t,
                )
            }
        }
        connection.rollback()
        shouldThrow<IllegalArgumentException> {
            NewOverride("E", "P", "chk-1", "r", OverrideStatus.RELEASED, "U1", null)
        }
    }

    @Test
    fun `INV-AC-20556 rows of another tenant never appear and cannot be released`() {
        val t1 = tenant()
        val t2 = tenant()
        val other = inTx(t2) { store.insert(releasedRow("chk-5")) }
        val mine = inTx(t2) { store.insert(pending("chk-6")) }
        inTx(t1) { store.entries("E", "P") } shouldBe emptyList()
        inTx(t1) { released.releasedCheckIds("E", "P") } shouldBe emptySet()
        inTx(t1) { store.release(mine.id, "U9") } shouldBe false
        inTx(t2) { store.entries("E", "P") } shouldContainExactlyInAnyOrder listOf(other, mine.copy())
    }

    @Test
    fun `INV-AC-20556 the released-checks read returns chk-5 only for the given data`() {
        val t = tenant()
        val other = tenant()
        inTx(t) { store.insert(releasedRow("chk-5")) }
        inTx(t) { store.insert(pending("chk-6")) }
        inTx(t) { store.insert(releasedRow("chk-5", "P2")) }
        inTx(other) { store.insert(releasedRow("chk-7")) }
        inTx(t) { released.releasedCheckIds("E", "P") } shouldBe setOf("chk-5")
        inTx(t) { released.releasedCheckIds("E", "P3") } shouldBe emptySet()
    }
}
