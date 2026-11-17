package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqPartitionLifecycle
import com.dynokore.gst.evidence.adapters.persistence.JooqRetentionRecords
import com.dynokore.gst.evidence.adapters.retention.JsonTierYearsReader
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEnd
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEndResult
import com.dynokore.gst.evidence.application.retention.DeletionAudit
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
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
 * INV-AC-24307: the partition lifecycle detaches, archives and drops the `prior_evidence` member (V24) of a unit with
 * the four chain tables, and archives its rows as one object ordered by transaction date, document number, id. A unit
 * without the member behaves as before. Real PostgreSQL through the repository fixtures, an in-memory object store.
 */
class PriorEvidenceLifecycleDbTest {
    private val lockPeriod: Duration = Duration.ofDays(30)
    private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
    private val chain = RecordKind.entries.map { it.table }
    private val types =
        mapOf(
            RecordKind.DECISION_TRACE to EvidenceType.DECISION_TRACE,
            RecordKind.EVIDENCE_PAYLOAD to EvidenceType.PAYLOAD,
            RecordKind.ACKNOWLEDGEMENT to EvidenceType.ACKNOWLEDGEMENT,
            RecordKind.AUDIT_LOG to EvidenceType.AUDIT_LOG,
        )

    private class UnitKey(
        val tenant: UUID,
        val entity: String = "E1",
        val period: String,
    )

    private class Memory : ObjectStore {
        val objects = mutableMapOf<String, ByteArray>()
        val retains = mutableMapOf<String, Instant>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            objects[key.value] = bytes
            retains[key.value] = retainUntil
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = objects.getValue(key.value)
    }

    /** Records every call in order; refuses the detach of [refuseDetachOf]. */
    private class Recorder(
        private val refuseDetachOf: String? = null,
    ) : (String) -> Unit {
        val calls = mutableListOf<String>()

        override fun invoke(call: String) {
            calls += call
            check(call != "detach:$refuseDetachOf") { "the detach is refused for $refuseDetachOf" }
        }
    }

    private class Audited : DeletionAudit {
        override fun append(
            tenant: String,
            partitionId: String,
            actorUserId: String,
            tailHashes: List<String>,
        ): String = "audit-1"
    }

    private val used = mutableListOf<UnitKey>()

    /** Detached tables are standalone until dropped: the shared schema is left as found. */
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

    private fun partitionId(u: UnitKey): String =
        checkNotNull(
            superuser(
                "select partition_id from evidence.retention_partition " +
                    "where tenant_id = ? and entity_ref = ? and period_from = ?",
                u.tenant,
                u.entity,
                u.period,
            ),
        )

    private fun state(u: UnitKey): String =
        checkNotNull(superuser("select state from evidence.retention_partition where partition_id = ?", partitionId(u)))

    private fun at(i: Instant): java.time.OffsetDateTime = i.atOffset(ZoneOffset.UTC)

    /**
     * A deletion-ready unit with two rows per chain table; with [prior] rows (document number to transaction date, in
     * insertion order) it also has the `prior_evidence` member.
     */
    private fun unit(
        tenant: UUID,
        period: String,
        prior: List<Pair<String, LocalDate>> = emptyList(),
    ): UnitKey {
        val u = UnitKey(tenant, period = period)
        used += u
        runtime(tenant) { c ->
            val ledgers = ChainWiring.appendLedgers(DSL.using(c, SQLDialect.POSTGRES))
            repeat(2) { n ->
                types.forEach { (kind, type) ->
                    ledgers
                        .getValue(kind)
                        .append(TenantId(tenant), EntityRef(u.entity), PeriodRef(period), type, "c$n".toByteArray())
                }
            }
        }
        if (prior.isNotEmpty()) {
            EvidenceDb.superuser().use { c ->
                RetentionPartitionFixture.ensurePriorEvidencePartition(c, tenant, u.entity, period)
                prior.forEach { RetentionPartitionFixture.insertPriorEvidence(c, tenant, u.entity, period, it) }
            }
        }
        authority(u)
        return u
    }

    /** The notice (window closed) and the two approvals of the deletion of [u]. */
    private fun authority(u: UnitKey) {
        val id = partitionId(u)
        val noticed = at(clock.instant().minus(Duration.ofDays(90)))
        val closed = at(clock.instant().minus(Duration.ofDays(60)))
        val statements =
            listOf(
                "insert into evidence.retention_notice values (?, ?, ?, 30, ?)" to
                    listOf(u.tenant, id, noticed, closed),
            ) +
                (0..1).map {
                    "insert into evidence.retention_approval values (?, ?, ?, 'retention.delete.approve', ?)" to
                        listOf(u.tenant, id, "u-approver-$it", at(clock.instant()))
                }
        statements.forEach { (sql, args) ->
            runtime(u.tenant) { c ->
                c.prepareStatement(sql).use { st ->
                    args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                    st.execute()
                }
            }
        }
    }

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
        return EffectiveRetention(reader, tenants, 5, JsonTierYearsReader())
    }

    private class Outcome(
        val result: DeleteAtRetentionEndResult?,
        val failure: Throwable?,
    )

    private fun delete(
        u: UnitKey,
        recorder: Recorder,
        store: ObjectStore,
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
                        Audited(),
                        JooqPartitionLifecycle(recording, store, clock, lockPeriod, recorder),
                        clock,
                    ).execute(u.tenant.toString(), "u-starter", partitionId(u))
                }
            Outcome(result, null)
        } catch (e: RuntimeException) {
            Outcome(null, e)
        }

    private val priorRows =
        listOf("DOC-B" to LocalDate.of(2010, 2, 1), "DOC-A" to LocalDate.of(2010, 1, 15))

    private fun key(
        u: UnitKey,
        table: String,
    ) = "retention-archive/${u.tenant}/${partitionId(u)}/$table.json"

    private fun documents(json: ByteArray): List<String> =
        Regex("\"(DOC-[A-Z0-9]+)\"").findAll(json.toString(Charsets.UTF_8)).map { it.groupValues[1] }.toList()

    @Test
    fun `INV-AC-24307 a unit with the prior member is detached, archived and dropped with the five tables in order`() {
        val tenant = UUID.randomUUID()
        val p1 = unit(tenant, "2010-Q1", priorRows)
        val recorder = Recorder()
        val outcome = delete(p1, recorder, Memory())
        outcome.failure shouldBe null
        outcome.result shouldBe DeleteAtRetentionEndResult.DELETED
        val five = chain + "prior_evidence"
        recorder.calls shouldBe five.map { "detach:$it" } + "archive" + five.map { "drop:$it" }
        recorder.calls.count { it == "archive" } shouldBe 1
        state(p1) shouldBe "DROPPED"
    }

    @Test
    fun `INV-AC-24307 a unit without the prior member makes exactly the four table calls and writes no prior object`() {
        val tenant = UUID.randomUUID()
        val p1 = unit(tenant, "2010-Q1", priorRows)
        val p2 = unit(tenant, "2010-Q2")
        val store = Memory()
        val recorder = Recorder()
        delete(p2, recorder, store).result shouldBe DeleteAtRetentionEndResult.DELETED
        recorder.calls shouldBe chain.map { "detach:$it" } + "archive" + chain.map { "drop:$it" }
        store.objects.keys.sorted() shouldBe chain.sorted().map { key(p2, it) }
        store.objects.keys.none { it.endsWith("prior_evidence.json") } shouldBe true
        state(p1) shouldBe "ATTACHED"
    }

    @Test
    fun `INV-AC-24307 the prior archive object is ordered by transaction date then document number and locked`() {
        val tenant = UUID.randomUUID()
        val p1 = unit(tenant, "2010-Q1", priorRows)
        val store = Memory()
        delete(p1, Recorder(), store).result shouldBe DeleteAtRetentionEndResult.DELETED
        store.objects.keys.sorted() shouldBe (chain + "prior_evidence").sorted().map { key(p1, it) }
        // inserted DOC-B (later date) first: the object is ordered by date, not by insertion
        documents(store.objects.getValue(key(p1, "prior_evidence"))) shouldBe listOf("DOC-A", "DOC-B")
        store.retains.getValue(key(p1, "prior_evidence")) shouldBe clock.instant().plus(lockPeriod)
    }

    @Test
    fun `INV-AC-24307 rows of one transaction date are ordered by document number`() {
        val tenant = UUID.randomUUID()
        val date = LocalDate.of(2010, 3, 1)
        val p1 = unit(tenant, "2010-Q1", listOf("DOC-9" to date, "DOC-1" to date, "DOC-5" to date))
        val store = Memory()
        delete(p1, Recorder(), store).result shouldBe DeleteAtRetentionEndResult.DELETED
        documents(store.objects.getValue(key(p1, "prior_evidence"))) shouldBe listOf("DOC-1", "DOC-5", "DOC-9")
    }

    @Test
    fun `INV-AC-24307 a failure of the prior detach is thrown and the sequence stops`() {
        val tenant = UUID.randomUUID()
        val p1 = unit(tenant, "2010-Q1", priorRows)
        val recorder = Recorder(refuseDetachOf = "prior_evidence")
        val outcome = delete(p1, recorder, Memory())
        outcome.result shouldBe null
        val message = outcome.failure?.message.orEmpty()
        message.contains("the detach is refused for prior_evidence") shouldBe true
        recorder.calls shouldBe chain.map { "detach:$it" } + "detach:prior_evidence"
        state(p1) shouldBe "ATTACHED"
    }
}
