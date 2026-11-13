package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.adapters.persistence.JooqFilingDueDates
import com.dynokore.gst.submission.adapters.persistence.JooqFilingFactStore
import com.dynokore.gst.submission.application.filing.AmbiguousDueDateException
import com.dynokore.gst.submission.application.filing.ConfirmFiling
import com.dynokore.gst.submission.application.filing.ConfirmFilingActions
import com.dynokore.gst.submission.application.filing.ConfirmFilingRequest
import com.dynokore.gst.submission.application.filing.ConfirmFilingResult
import com.dynokore.gst.submission.application.filing.NewFilingFact
import com.dynokore.gst.submission.application.gate.GateModes
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.domain.filing.FilingConflict
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Tests of `submission.filing_fact`, its jOOQ store and the due-date adapter (INV-T-20626, INV-REQ-917). */
class FilingFactStoreDbTest {
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val t0 = Instant.parse("2026-04-18T08:00:00Z")
    private val clock = MutableClock(t0)
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private var bound: UUID = UUID.randomUUID()
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
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val store = JooqFilingFactStore(dsl, tenants, clock)
    private val dueDates = JooqFilingDueDates(dsl, tenants)
    private val created = mutableListOf<UUID>()
    private val deadline = Deadline.after(Duration.ofSeconds(5), Clock.fixed(t0, ZoneOffset.UTC))
    private val u1 = Principal("u1-id", PrincipalKind.HUMAN)

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use {
                    it.execute("delete from submission.filing_fact where tenant_id = '$t'")
                    it.execute("delete from submission.period_due_date where tenant_id = '$t'")
                }
            }
        }
    }

    private fun tenant() = UUID.randomUUID().also { created += it }

    private fun <R> inTx(
        tenant: UUID,
        commit: Boolean = true,
        block: () -> R,
    ): R {
        SignedBindings.bind(connection, tenant)
        bound = tenant
        return try {
            block().also { if (commit) connection.commit() }
        } finally {
            if (!commit) connection.rollback()
        }
    }

    private fun owner(sql: String) = SubmissionDb.superuser().use { c -> c.createStatement().use { it.execute(sql) } }

    private fun ownerStrings(sql: String): List<String> =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

    private fun dueDate(t: UUID) =
        owner(
            "insert into submission.period_due_date (tenant_id, entity_ref, period_ref, filing_due_date, " +
                "due_date_version) values ('$t', 'E', 'P', date '2026-04-30', 1)",
        )

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    private fun replicaFor(t: UUID): AuthorityReplica {
        val authClock = Clock.fixed(t0, ZoneOffset.UTC)
        val role =
            roleMap
                .rolesFor(ConfirmFilingActions.PERSON)
                .keys
                .sorted()
                .first()
        val grant = RoleGrant(role, GrantScope.ENTITY, "E", t0.minusSeconds(60))
        val record = AuthorityRecord.RoleGrantRecord("g-$t", RoleGrantState.ACTIVE, grant)
        return AuthorityReplica(authClock, roleMap).also {
            it.onSnapshotComplete(0)
            it.accept(AuthorityChanged(TenantId(t), u1.id, 1, record))
        }
    }

    private fun useCase(t: UUID) =
        ConfirmFiling(
            replicaFor(t),
            dueDates,
            GateModes { GateMode.BLOCKING },
            object : LatestChecks {
                override fun latest(
                    entityRef: String,
                    periodRef: String,
                ) = LatestCheck("chk-1", CheckVerdict.READY)

                override fun knownCheckIds(
                    entityRef: String,
                    periodRef: String,
                ): Set<String> = emptySet()
            },
            ReleasedOverrideChecks { _, _ -> emptySet() },
            store,
        )

    private fun call(
        t: UUID,
        day: Int,
        ack: String,
        correction: Boolean = false,
    ): ConfirmFilingResult.Recorded =
        inTx(t) {
            useCase(t)
                .handle(
                    ConfirmFilingRequest(
                        TenantId(t),
                        u1,
                        "E",
                        "P",
                        LocalDate.of(2026, 4, day),
                        ack,
                        null,
                        correction,
                    ),
                    deadline,
                ).shouldBeInstanceOf<ConfirmFilingResult.Recorded>()
        }

    @Test
    fun `INV-AC-20625 first fact second date repeat and correction through the use case`() {
        val t = tenant()
        val t2 = tenant()
        dueDate(t)
        owner(
            "insert into submission.filing_fact (tenant_id, entity_ref, period_ref, filed_at, acknowledgement_ref, " +
                "correction, recorded_by, recorded_at) values ('$t2', 'E', 'P', date '2026-04-25', 'X1', false, " +
                "'someone', now())",
        )
        val first = call(t, 18, "A1")
        first.governingFiledAt shouldBe LocalDate.of(2026, 4, 18)
        first.conflict shouldBe null
        val rows = inTx(t) { store.facts("E", "P") }
        rows.size shouldBe 1
        rows[0].filedAt shouldBe LocalDate.of(2026, 4, 18)
        rows[0].recordedBy shouldBe u1.id
        rows[0].correction shouldBe false

        val second = call(t, 20, "A2")
        second.governingFiledAt shouldBe LocalDate.of(2026, 4, 18)
        second.conflict shouldBe FilingConflict(LocalDate.of(2026, 4, 20))
        second.conflict!!.code shouldBe "PER-010"
        val afterSecond = inTx(t) { store.facts("E", "P") }
        afterSecond.size shouldBe 2
        afterSecond[0] shouldBe rows[0]

        val repeat = call(t, 20, "A2")
        repeat.conflict shouldBe FilingConflict(LocalDate.of(2026, 4, 20))
        repeat.governingFiledAt shouldBe LocalDate.of(2026, 4, 18)
        inTx(t) { store.facts("E", "P") }.size shouldBe 2

        val fix = call(t, 19, "A3", correction = true)
        fix.governingFiledAt shouldBe LocalDate.of(2026, 4, 19)
        fix.conflict shouldBe null
        val all = inTx(t) { store.facts("E", "P") }
        all.size shouldBe 3
        all[2].correction shouldBe true
        all.map { it.recordedBy } shouldBe listOf(u1.id, u1.id, u1.id)
        inTx(t2) { store.facts("E", "P") }.map { it.acknowledgementRef } shouldBe listOf("X1")
        inTx(t) { store.facts("E", "P") }.none { it.acknowledgementRef == "X1" } shouldBe true

        inTx(t) { store.facts("E", "P") } shouldBe all
        updateAndDeleteAreRefused(t)
    }

    private fun updateAndDeleteAreRefused(t: UUID) {
        for (sql in listOf(
            "update submission.filing_fact set recorded_by = 'x'",
            "delete from submission.filing_fact",
        )) {
            val e = assertThrows<Exception> { inTx(t) { DSL.using(connection, SQLDialect.POSTGRES).execute(sql) } }
            e.message.orEmpty().contains("permission denied") shouldBe true
            connection.rollback()
        }
        ownerStrings("select has_table_privilege('submission_runtime', 'submission.filing_fact', 'UPDATE')") shouldBe
            listOf("f")
        ownerStrings("select has_table_privilege('submission_runtime', 'submission.filing_fact', 'DELETE')") shouldBe
            listOf("f")
    }

    @Test
    fun `the first stored row carries the clock instant and the portal reference, null stays null`() {
        val t = tenant()
        dueDate(t)
        inTx(t) { store.append(fact("A1", portal = "PR-1")) }
        inTx(t) { store.append(fact("A2", portal = null)) }
        val rows = inTx(t) { store.facts("E", "P") }
        rows[0].recordedAt shouldBe t0
        rows[0].portalRef shouldBe "PR-1"
        rows[1].portalRef shouldBe null
    }

    @Test
    fun `after the clock advances a repeat adds no row and facts keep the first recordedAt`() {
        val t = tenant()
        dueDate(t)
        inTx(t) { store.append(fact("A1", portal = "PR-1")) }
        clock.now = t0.plusSeconds(3600)
        inTx(t) { store.append(fact("A1", portal = "PR-2")) }
        val rows = inTx(t) { store.facts("E", "P") }
        rows.size shouldBe 1
        rows[0].recordedAt shouldBe t0
        rows[0].portalRef shouldBe "PR-1"
    }

    @Test
    fun `a row of another tenant is not visible and the runtime role holds select and insert only`() {
        val t = tenant()
        val t2 = tenant()
        dueDate(t)
        inTx(t) { store.append(fact("A1", portal = null)) }
        inTx(t2) { store.facts("E", "P") } shouldBe emptyList()
        ownerStrings("select has_table_privilege('submission_runtime', 'submission.filing_fact', 'SELECT')") shouldBe
            listOf("t")
        ownerStrings("select has_table_privilege('submission_runtime', 'submission.filing_fact', 'INSERT')") shouldBe
            listOf("t")
    }

    @Test
    fun `JooqFilingDueDates answers the date for one row and null for none`() {
        val t = tenant()
        dueDate(t)
        inTx(t) { dueDates.dueDateOf("E", "P") } shouldBe LocalDate.of(2026, 4, 30)
        inTx(t) { dueDates.dueDateOf("E", "P2") } shouldBe null
        inTx(tenant()) { dueDates.dueDateOf("E", "P") } shouldBe null
    }

    /** The key of `period_due_date` forbids a second row, so the more-than-one case runs over a stubbed result set. */
    @Test
    fun `JooqFilingDueDates throws for more than one row`() {
        val mock: Connection =
            MockConnection { ctx ->
                val create = DSL.using(SQLDialect.POSTGRES)
                val result = create.newResult(DSL.field("filing_due_date", LocalDate::class.java))
                result.add(
                    create.newRecord(DSL.field("filing_due_date", LocalDate::class.java)).also {
                        it.set(DSL.field("filing_due_date", LocalDate::class.java), LocalDate.of(2026, 4, 30))
                    },
                )
                result.add(
                    create.newRecord(DSL.field("filing_due_date", LocalDate::class.java)).also {
                        it.set(DSL.field("filing_due_date", LocalDate::class.java), LocalDate.of(2026, 5, 31))
                    },
                )
                arrayOf(MockResult(2, result))
            }
        val stubbed = JooqFilingDueDates(DSL.using(mock, SQLDialect.POSTGRES), tenants)
        assertThrows<AmbiguousDueDateException> { stubbed.dueDateOf("E", "P") }
    }

    private fun fact(
        ack: String,
        portal: String?,
    ) = NewFilingFact("E", "P", LocalDate.of(2026, 4, 18), ack, portal, false, u1.id)
}
