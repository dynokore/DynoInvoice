package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-22962 (V21): the chain tables are range partitions with a registry; detach, archive and drop run through
 * SECURITY DEFINER functions that record the tail hash; the runtime login cannot delete or drop. Every case uses its
 * own random tenants, so the cases do not see each other's rows.
 */
class RetentionPartitionsDbTest {
    private val tables = RecordKind.entries.map { it.table }
    private val types =
        mapOf(
            RecordKind.DECISION_TRACE to EvidenceType.DECISION_TRACE,
            RecordKind.EVIDENCE_PAYLOAD to EvidenceType.PAYLOAD,
            RecordKind.ACKNOWLEDGEMENT to EvidenceType.ACKNOWLEDGEMENT,
            RecordKind.AUDIT_LOG to EvidenceType.AUDIT_LOG,
        )

    private data class Key(
        val tenant: UUID,
        val entity: String,
        val period: String,
    )

    /** The fixture of the criterion: T1/E1 with P1 (two rows per table) and P2, and T2/E3 with P1. */
    private class Fixture {
        val t1: UUID = UUID.randomUUID()
        val t2: UUID = UUID.randomUUID()
        val p1 = Key(t1, "E1", "P1")
        val p2 = Key(t1, "E1", "P2")
        val other = Key(t2, "E3", "P1")
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
            }
        }

    private fun append(
        key: Key,
        times: Int = 1,
    ) = runtime(key.tenant) { c ->
        val ledgers = ChainWiring.appendLedgers(DSL.using(c, SQLDialect.POSTGRES))
        repeat(times) { n ->
            types.forEach { (kind, type) ->
                ledgers
                    .getValue(kind)
                    .append(TenantId(key.tenant), EntityRef(key.entity), PeriodRef(key.period), type, "c${key.period}$n".toByteArray())
            }
        }
    }

    private fun fixture(): Fixture =
        Fixture().also {
            append(it.p1, 2)
            append(it.p2)
            append(it.other)
        }

    private fun <T> superuser(block: (Connection) -> T): T = EvidenceDb.superuser().use(block)

    private fun count(
        table: String,
        key: Key,
    ): Int =
        superuser { c ->
            c
                .prepareStatement("select count(*) from evidence.$table where tenant_id = ? and entity_ref = ? and period_ref = ?")
                .use {
                    it.setObject(1, key.tenant)
                    it.setString(2, key.entity)
                    it.setString(3, key.period)
                    it.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } }
                }
        }

    private fun counts(key: Key): Map<String, Int> = tables.associateWith { count(it, key) }

    private fun partitionId(key: Key): String =
        superuser { c ->
            c
                .prepareStatement(
                    "select partition_id from evidence.retention_partition " +
                        "where tenant_id = ? and entity_ref = ? and period_from = ?",
                ).use {
                    it.setObject(1, key.tenant)
                    it.setString(2, key.entity)
                    it.setString(3, key.period)
                    it.executeQuery().use { rs -> rs.next().let { rs.getString(1) } }
                }
        }

    private fun state(id: String): String =
        superuser { c ->
            c.prepareStatement("select state from evidence.retention_partition where partition_id = ?").use {
                it.setString(1, id)
                it.executeQuery().use { rs -> rs.next().let { rs.getString(1) } }
            }
        }

    private fun lastHash(
        table: String,
        key: Key,
    ): String =
        superuser { c ->
            c
                .prepareStatement(
                    "select content_hash from evidence.$table where tenant_id = ? and entity_ref = ? and period_ref = ? " +
                        "order by seq desc limit 1",
                ).use {
                    it.setObject(1, key.tenant)
                    it.setString(2, key.entity)
                    it.setString(3, key.period)
                    it.executeQuery().use { rs -> rs.next().let { rs.getString(1) } }
                }
        }

    private fun runtimeStatement(
        tenant: UUID,
        sql: String,
        vararg args: Any?,
    ): String? =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                c.prepareStatement(sql).use { st ->
                    args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                    st.execute()
                }
                c.commit()
                null
            } catch (e: SQLException) {
                c.rollback()
                e.sqlState
            }
        }

    private val now: Instant get() = Instant.now()

    private fun notice(
        key: Key,
        closes: Instant,
    ) = runtimeStatement(
        key.tenant,
        "insert into evidence.retention_notice values (?, ?, now(), 30, ?)",
        key.tenant,
        partitionId(key),
        java.sql.Timestamp.from(closes),
    ).shouldBeNull()

    private fun approve(
        key: Key,
        user: String,
    ): String? =
        runtimeStatement(
            key.tenant,
            "insert into evidence.retention_approval values (?, ?, ?, 'retention.delete.approve', now())",
            key.tenant,
            partitionId(key),
            user,
        )

    private fun hold(key: Key) =
        runtimeStatement(
            key.tenant,
            "insert into evidence.legal_hold (tenant_id, entity_ref, period_ref, reason, status, placed_at, placed_by) " +
                "values (?, ?, ?, 'audit', 'ACTIVE', now(), 'u-hold')",
            key.tenant,
            key.entity,
            key.period,
        ).shouldBeNull()

    private fun String?.shouldBeNull() = this shouldBe null

    /** A partition ready for deletion: closed notice window and two distinct approvers. */
    private fun ready(key: Key) {
        notice(key, now.minusSeconds(DAY))
        approve(key, "u-one").shouldBeNull()
        approve(key, "u-two").shouldBeNull()
    }

    private fun detach(
        key: Key,
        retentionEnd: Instant?,
        audit: String? = "audit-1",
    ): String? =
        runtimeStatement(
            key.tenant,
            "select evidence.detach_expired_partition(?, ?, ?)",
            partitionId(key),
            audit,
            retentionEnd?.let { java.sql.Timestamp.from(it) },
        )

    private fun assertRefused(
        key: Key,
        before: Map<String, Int>,
        sqlState: String?,
    ) {
        (sqlState != null) shouldBe true
        state(partitionId(key)) shouldBe "ATTACHED"
        counts(key) shouldBe before
    }

    @Test
    fun `INV-AC-22962 each chain table is range partitioned and the registry has one row per tenant entity and period`() {
        val f = fixture()
        superuser { c ->
            c
                .prepareStatement(
                    "select c.relname from pg_partitioned_table p join pg_class c on c.oid = p.partrelid " +
                        "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'evidence' and p.partstrat = 'r'",
                ).use { st ->
                    st.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() shouldBe tables.toSet()
                    }
                }
        }
        listOf(f.p1, f.p2, f.other).forEach { key ->
            superuser { c ->
                c
                    .prepareStatement(
                        "select count(*), min(partition_id) from evidence.retention_partition " +
                            "where tenant_id = ? and entity_ref = ? and period_from = ? and period_to = ?",
                    ).use {
                        it.setObject(1, key.tenant)
                        it.setString(2, key.entity)
                        it.setString(3, key.period)
                        it.setString(4, key.period)
                        it.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1) shouldBe 1
                            rs.getString(2).startsWith("rp_") shouldBe true
                        }
                    }
            }
        }
        // the append of the fixture reused the partition of (T1, E1, P1): still one row for the two appends
        superuser { c ->
            c.prepareStatement("select count(*) from evidence.retention_partition where tenant_id = ?").use {
                it.setObject(1, f.t1)
                it.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } } shouldBe 2
            }
        }
    }

    @Test
    fun `INV-AC-22962 the detach removes only the rows of the partition and records the tail hash per chain table`() {
        val f = fixture()
        ready(f.p1)
        val id = partitionId(f.p1)
        val otherBefore = counts(f.p2) to counts(f.other)
        val tails = tables.associateWith { lastHash(it, f.p1) }
        counts(f.p1) shouldBe tables.associateWith { 2 }
        detach(f.p1, now.minusSeconds(DAY)) shouldBe null
        counts(f.p1) shouldBe tables.associateWith { 0 }
        (counts(f.p2) to counts(f.other)) shouldBe otherBefore
        state(id) shouldBe "DETACHED"
        superuser { c ->
            c.prepareStatement("select tail_hashes ->> ? from evidence.retention_partition where partition_id = ?").use { st ->
                tables.forEach { table ->
                    st.setString(1, table)
                    st.setString(2, id)
                    st.executeQuery().use { rs -> rs.next().let { rs.getString(1) } } shouldBe tails.getValue(table)
                }
            }
        }
    }

    @Test
    fun `INV-AC-22962 the drop before the detach is refused, and after the detach and the archive mark it succeeds`() {
        val f = fixture()
        ready(f.p1)
        val id = partitionId(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, runtimeStatement(f.t1, "select evidence.drop_detached_partition(?)", id))
        detach(f.p1, now.minusSeconds(DAY)) shouldBe null
        // a detached, not yet archived partition is not dropped either
        (runtimeStatement(f.t1, "select evidence.drop_detached_partition(?)", id) != null) shouldBe true
        state(id) shouldBe "DETACHED"
        runtimeStatement(f.t1, "select evidence.mark_partition_archived(?, 'vol-1')", id) shouldBe null
        state(id) shouldBe "ARCHIVED"
        runtimeStatement(f.t1, "select evidence.drop_detached_partition(?)", id) shouldBe null
        state(id) shouldBe "DROPPED"
        superuser { c ->
            c.prepareStatement("select count(*) from pg_class where relname like ?").use {
                it.setString(1, "%_$id")
                it.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } } shouldBe 0
            }
        }
    }

    @Test
    fun `INV-AC-22962 the detach with an active hold is refused`() {
        val f = fixture()
        ready(f.p1)
        hold(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, detach(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with the notice window still open is refused`() {
        val f = fixture()
        notice(f.p1, now.plusSeconds(DAY))
        approve(f.p1, "u-one").shouldBeNull()
        approve(f.p1, "u-two").shouldBeNull()
        val before = counts(f.p1)
        assertRefused(f.p1, before, detach(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with no notice row is refused`() {
        val f = fixture()
        approve(f.p1, "u-one").shouldBeNull()
        approve(f.p1, "u-two").shouldBeNull()
        val before = counts(f.p1)
        assertRefused(f.p1, before, detach(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with a retention end later than now is refused`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, detach(f.p1, now.plusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with a null retention end is refused`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, detach(f.p1, null))
    }

    @Test
    fun `INV-AC-22962 the detach with one approval is refused`() {
        val f = fixture()
        notice(f.p1, now.minusSeconds(DAY))
        approve(f.p1, "u-one").shouldBeNull()
        val before = counts(f.p1)
        assertRefused(f.p1, before, detach(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with the same approver twice is refused`() {
        val f = fixture()
        notice(f.p1, now.minusSeconds(DAY))
        approve(f.p1, "u-one").shouldBeNull()
        (approve(f.p1, "u-one") == "23505") shouldBe true
        val before = counts(f.p1)
        assertRefused(f.p1, before, detach(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach without an audit reference is refused`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, detach(f.p1, now.minusSeconds(DAY), audit = " "))
    }

    @Test
    fun `INV-AC-22962 another tenant cannot detach the partition`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        val refused =
            runtimeStatement(
                f.t2,
                "select evidence.detach_expired_partition(?, 'a', ?)",
                partitionId(f.p1),
                java.sql.Timestamp.from(now.minusSeconds(DAY)),
            )
        assertRefused(f.p1, before, refused)
    }

    @Test
    fun `INV-AC-22962 DELETE DROP TABLE and TRUNCATE by the runtime login are refused`() {
        val f = fixture()
        val before = counts(f.p1)
        tables.forEach { table ->
            runtimeStatement(f.t1, "delete from evidence.$table") shouldBe "42501"
            runtimeStatement(f.t1, "drop table evidence.$table") shouldBe "42501"
            runtimeStatement(f.t1, "truncate evidence.$table") shouldBe "42501"
            runtimeStatement(f.t1, "alter table evidence.$table detach partition evidence.${table}_default") shouldBe "42501"
            runtimeStatement(f.t1, "drop table evidence.${table}_${partitionId(f.p1)}") shouldBe "42501"
        }
        counts(f.p1) shouldBe before
    }

    @Test
    fun `INV-AC-22962 an append for a new period succeeds and lands in a partition of the same tenant`() {
        val f = fixture()
        val fresh = Key(f.t1, "E1", "P3")
        append(fresh)
        val id = partitionId(fresh)
        id shouldBe partitionId(fresh)
        tables.forEach { table ->
            superuser { c ->
                c
                    .prepareStatement(
                        "select tableoid::regclass::text from evidence.$table where tenant_id = ? and period_ref = 'P3'",
                    ).use {
                        it.setObject(1, f.t1)
                        it.executeQuery().use { rs ->
                            rs.next() shouldBe true
                            rs.getString(1) shouldBe "evidence.${table}_$id"
                        }
                    }
            }
        }
        superuser { c ->
            c.prepareStatement("select tenant_id from evidence.retention_partition where partition_id = ?").use {
                it.setString(1, id)
                it.executeQuery().use { rs -> rs.next().let { rs.getObject(1) } } shouldBe f.t1
            }
        }
        // a tenant never creates a partition for another tenant
        runtimeStatement(f.t2, "select evidence.ensure_partition(?, 'E1', 'P9')", f.t1) shouldBe "42501"
    }

    @Test
    fun `INV-AC-22962 the functions are security definer with a fixed search_path and executable by the runtime login only`() {
        superuser { c ->
            c
                .prepareStatement(
                    "select p.proname, p.prosecdef, p.proconfig::text, has_function_privilege('evidence_runtime', p.oid, 'execute'), " +
                        "has_function_privilege('public', p.oid, 'execute') from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                        "where n.nspname = 'evidence' and p.proname = any (?)",
                ).use { st ->
                    st.setArray(
                        1,
                        c.createArrayOf(
                            "text",
                            arrayOf("ensure_partition", "detach_expired_partition", "mark_partition_archived", "drop_detached_partition"),
                        ),
                    )
                    st.executeQuery().use { rs ->
                        var seen = 0
                        while (rs.next()) {
                            seen++
                            rs.getBoolean(2) shouldBe true
                            rs.getString(3)!!.contains("search_path=evidence, pg_temp") shouldBe true
                            rs.getBoolean(4) shouldBe true
                            rs.getBoolean(5) shouldBe false
                        }
                        seen shouldBe 4
                    }
                }
        }
    }

    private companion object {
        const val DAY = 86_400L
    }
}
