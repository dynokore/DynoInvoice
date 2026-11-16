package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqPartitionLifecycle
import com.dynokore.gst.evidence.adapters.persistence.JooqRetentionRecords
import com.dynokore.gst.evidence.adapters.persistence.PeriodText
import com.dynokore.gst.evidence.adapters.retention.JsonTierYearsReader
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEnd
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEndResult
import com.dynokore.gst.evidence.application.retention.DeletionAudit
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
import com.dynokore.gst.evidence.application.retention.RetentionPolicy
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-22964 at the database: the readers of [JooqRetentionRecords] and the calls of [JooqPartitionLifecycle] on the
 * four chain tables, over the real PostgreSQL of V21 and V22. Every case uses its own random tenant. The rules of the
 * database refuse a hold, a missing notice and an unreached retention end whatever the use case says.
 */
@Suppress("LargeClass", "TooManyFunctions") // one DB test class for the stores of one task
class RetentionRecordsDbTest {
    private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
    private val tables = RecordKind.entries.map { it.table }
    private val types =
        mapOf(
            RecordKind.DECISION_TRACE to EvidenceType.DECISION_TRACE,
            RecordKind.EVIDENCE_PAYLOAD to EvidenceType.PAYLOAD,
            RecordKind.ACKNOWLEDGEMENT to EvidenceType.ACKNOWLEDGEMENT,
            RecordKind.AUDIT_LOG to EvidenceType.AUDIT_LOG,
        )

    private class Fixture(
        val tenant: UUID = UUID.randomUUID(),
        val entity: String = "E1",
        val period: String = "2010-Q1",
    )

    private class Memory : ObjectStore {
        val objects = mutableMapOf<String, ByteArray>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            objects[key.value] = bytes
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = objects.getValue(key.value)
    }

    /** Records every call of the three functions in order, and refuses the detach of [refuseDetachOf]. */
    private class Recorder(
        private val refuseDetachOf: String? = null,
    ) : (String) -> Unit {
        val calls = mutableListOf<String>()

        override fun invoke(call: String) {
            calls += call
            check(call != "detach:$refuseDetachOf") { "the detach is refused for $refuseDetachOf" }
        }
    }

    private val used = mutableListOf<Fixture>()

    /** The detached tables of a unit are standalone tables until dropped: the shared schema is left as found. */
    @AfterEach
    fun dropDetachedTables() {
        EvidenceDb.superuser().use { c ->
            used.forEach { u ->
                val statements =
                    c
                        .prepareStatement(
                            "select 'drop table evidence.' || m.partition_table " +
                                "from evidence.retention_partition_member m " +
                                "join pg_class t on t.relname = m.partition_table " +
                                "and t.relnamespace = 'evidence'::regnamespace " +
                                "where m.tenant_id = ? and not t.relispartition",
                        ).use { st ->
                            st.setObject(1, u.tenant)
                            st.executeQuery().use { rs ->
                                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                            }
                        }
                c.createStatement().use { s -> statements.forEach { s.execute(it) } }
            }
        }
        used.clear()
    }

    private fun <T> runtime(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(c).also { c.commit() }
            } catch (e: SQLException) {
                c.rollback()
                throw e
            } catch (e: RuntimeException) {
                c.rollback()
                throw e
            }
        }

    private fun statement(
        tenant: UUID,
        sql: String,
        vararg args: Any?,
    ) = runtime(tenant) { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.execute()
        }
    }

    private fun superuser(
        sql: String,
        vararg args: Any?,
    ): String? =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun fixture(
        period: String = "2010-Q1",
        notice: Boolean = true,
        approvals: Int = 2,
        hold: Boolean = false,
    ): Fixture {
        val u = Fixture(period = period)
        used += u
        runtime(u.tenant) { c ->
            val ledgers = ChainWiring.appendLedgers(DSL.using(c, SQLDialect.POSTGRES))
            repeat(2) { n ->
                types.forEach { (kind, type) ->
                    ledgers
                        .getValue(kind)
                        .append(TenantId(u.tenant), EntityRef(u.entity), PeriodRef(u.period), type, "c$n".toByteArray())
                }
            }
        }
        authority(u, notice, approvals, hold)
        return u
    }

    private fun authority(
        u: Fixture,
        notice: Boolean,
        approvals: Int,
        hold: Boolean,
    ) {
        val id = partitionId(u)
        if (notice) {
            statement(
                u.tenant,
                "insert into evidence.retention_notice values " +
                    "(?, ?, ?, 30, ?)",
                u.tenant,
                id,
                at(clock.instant().minus(Duration.ofDays(90))),
                at(clock.instant().minus(Duration.ofDays(60))),
            )
        }
        repeat(approvals) {
            statement(
                u.tenant,
                "insert into evidence.retention_approval values (?, ?, ?, 'retention.delete.approve', ?)",
                u.tenant,
                id,
                "u-approver-$it",
                at(clock.instant()),
            )
        }
        if (hold) {
            statement(
                u.tenant,
                "insert into evidence.legal_hold " +
                    "(tenant_id, entity_ref, period_ref, reason, status, placed_at, placed_by) " +
                    "values (?, ?, ?, 'audit', 'ACTIVE', ?, 'u-hold')",
                u.tenant,
                u.entity,
                u.period,
                at(clock.instant()),
            )
        }
    }

    private fun at(i: Instant): java.time.OffsetDateTime = i.atOffset(ZoneOffset.UTC)

    private fun partitionId(u: Fixture): String =
        checkNotNull(
            superuser(
                "select partition_id from evidence.retention_partition " +
                    "where tenant_id = ? and entity_ref = ? and period_from = ?",
                u.tenant,
                u.entity,
                u.period,
            ),
        )

    private fun rows(u: Fixture): Map<String, Int> =
        tables.associateWith {
            checkNotNull(
                superuser(
                    "select count(*) from evidence.$it where tenant_id = ? and entity_ref = ? and period_ref = ?",
                    u.tenant,
                    u.entity,
                    u.period,
                ),
            ).toInt()
        }

    private fun lastHash(
        u: Fixture,
        table: String,
    ): String? =
        superuser(
            "select content_hash from evidence.$table where tenant_id = ? and entity_ref = ? and period_ref = ? " +
                "order by seq desc limit 1",
            u.tenant,
            u.entity,
            u.period,
        )

    private fun state(u: Fixture): String =
        checkNotNull(superuser("select state from evidence.retention_partition where partition_id = ?", partitionId(u)))

    /** The years of every tier are the catalogue default, 5: the stub reader selects no LONGER value. */
    private fun effective(tenant: UUID): EffectiveRetention {
        val reader =
            object : PolicyReader {
                override fun resolve(
                    key: VariationPointKey,
                    scope: PolicyScope,
                    at: LocalDate,
                ) = ResolvedSetting(key, at, listOf(SelectedOption("YEARS_5")), PolicySource.CatalogueDefault)
            }
        val tenants =
            object : TenantContext {
                override fun current() = TenantId(tenant)

                override fun currentActor(): String? = null

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        return EffectiveRetention(reader, tenants, DEFAULT_YEARS, JsonTierYearsReader())
    }

    private class Audited : DeletionAudit {
        val tails = mutableListOf<List<String>>()

        override fun append(
            tenant: String,
            partitionId: String,
            actorUserId: String,
            tailHashes: List<String>,
        ): String {
            tails += tailHashes
            return "audit-${tails.size}"
        }
    }

    private class Outcome(
        val result: DeleteAtRetentionEndResult?,
        val failure: Throwable?,
    )

    private fun delete(
        u: Fixture,
        recorder: Recorder,
        store: ObjectStore,
        audit: Audited = Audited(),
    ): Outcome =
        try {
            val result =
                runtime(u.tenant) { c ->
                    val recording = DSL.using(c, SQLDialect.POSTGRES)
                    val records = JooqRetentionRecords(recording, effective(u.tenant), clock)
                    DeleteAtRetentionEnd(
                        records,
                        records,
                        records,
                        records,
                        records,
                        records,
                        audit,
                        JooqPartitionLifecycle(recording, store, clock, recorder),
                        clock,
                    ).execute(u.tenant.toString(), "u-starter", partitionId(u))
                }
            Outcome(result, null)
        } catch (e: RuntimeException) {
            Outcome(null, e)
        }

    private val fourChainTables =
        listOf("decision_trace", "evidence_payload", "acknowledgement", "audit_log")

    @Test
    fun `INV-AC-22964 a deletion detaches and drops each of the four chain tables once`() {
        val u = fixture()
        val store = Memory()
        val before = rows(u)
        before.values.all { it == 2 } shouldBe true
        val tails =
            tables.map { "$it=" + lastHash(u, it) }
        val audit = Audited()
        val recorder = Recorder()
        val outcome = delete(u, recorder, store, audit)
        outcome.failure shouldBe null
        outcome.result shouldBe DeleteAtRetentionEndResult.DELETED
        recorder.calls shouldBe
            fourChainTables.map { "detach:$it" } + "archive" + fourChainTables.map { "drop:$it" }
        recorder.calls.count { it.startsWith("detach:") } shouldBe fourChainTables.size
        rows(u).values.all { it == 0 } shouldBe true
        state(u) shouldBe "DROPPED"
        audit.tails shouldBe listOf(tails)
        store.objects.keys.sorted() shouldBe
            fourChainTables.sorted().map { "retention-archive/${u.tenant}/${partitionId(u)}/$it.json" }
        store.objects.values.all { it.size > 2 } shouldBe true
    }

    @Test
    fun `INV-AC-22964 a refused detach on one chain table stops the deletion`() {
        val u = fixture()
        val store = Memory()
        val recorder = Recorder(refuseDetachOf = "acknowledgement")
        val outcome = delete(u, recorder, store)
        outcome.result shouldBe null
        (outcome.failure != null) shouldBe true
        recorder.calls shouldBe listOf("detach:decision_trace", "detach:evidence_payload", "detach:acknowledgement")
        recorder.calls.none { it.startsWith("drop:") || it == "archive" || it == "detach:audit_log" } shouldBe true
        state(u) shouldBe "ATTACHED"
        rows(u).values.all { it == 2 } shouldBe true
    }

    @Test
    fun `INV-AC-22964 an active hold refuses and the rows remain`() {
        val u = fixture(hold = true)
        val recorder = Recorder()
        val outcome = delete(u, recorder, Memory())
        outcome.result shouldBe DeleteAtRetentionEndResult.HOLD_IN_FORCE
        recorder.calls shouldBe emptyList()
        rows(u).values.all { it == 2 } shouldBe true
        state(u) shouldBe "ATTACHED"
    }

    @Test
    fun `INV-AC-22964 a partition with no notice row refuses and the rows remain`() {
        val u = fixture(notice = false)
        val outcome = delete(u, Recorder(), Memory())
        outcome.result shouldBe DeleteAtRetentionEndResult.NOTICE_MISSING
        rows(u).values.all { it == 2 } shouldBe true
    }

    @Test
    fun `INV-AC-22964 a retention end later than now refuses and the rows remain`() {
        val u = fixture(period = "2099-Q1")
        val outcome = delete(u, Recorder(), Memory())
        outcome.result shouldBe DeleteAtRetentionEndResult.RETENTION_NOT_ENDED
        rows(u).values.all { it == 2 } shouldBe true
    }

    @Test
    fun `INV-AC-22964 a period text the service cannot date gives no retention end and refuses`() {
        val u = fixture(period = "P1")
        val outcome = delete(u, Recorder(), Memory())
        outcome.result shouldBe DeleteAtRetentionEndResult.RETENTION_NOT_ENDED
        rows(u).values.all { it == 2 } shouldBe true
    }

    @Test
    fun `INV-AC-22964 one approval is not two and refuses`() {
        val u = fixture(approvals = 1)
        val outcome = delete(u, Recorder(), Memory())
        outcome.result shouldBe DeleteAtRetentionEndResult.APPROVALS_MISSING
        rows(u).values.all { it == 2 } shouldBe true
    }

    @Test
    fun `INV-AC-22964 the period text is read only as a quarter, a month, a day or a year`() {
        PeriodText.range("2026-Q1") shouldBe (LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 3, 31))
        PeriodText.range("2026-Q4") shouldBe (LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 12, 31))
        PeriodText.range("2026-02") shouldBe (LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28))
        PeriodText.range("2026-02-03") shouldBe (LocalDate.of(2026, 2, 3) to LocalDate.of(2026, 2, 3))
        PeriodText.range("2026") shouldBe (LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 12, 31))
        val notPeriods = listOf("P1", "2026-Q5", "2026-Q0", "2026-13", "2026-02-30", "2026-q1", " 2026")
        (notPeriods + listOf("2026-Q1\n", "", "20261")).forEach {
            PeriodText.range(it) shouldBe null
        }
    }

    private companion object {
        const val DEFAULT_YEARS = 5
    }
}
