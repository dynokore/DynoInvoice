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
import org.junit.jupiter.api.AfterEach
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
@Suppress("LargeClass") // one DB test class for the one migration (INV-AC-22962)
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
                    .append(
                        TenantId(key.tenant),
                        EntityRef(key.entity),
                        PeriodRef(key.period),
                        type,
                        "c${key.period}$n".toByteArray(),
                    )
            }
        }
    }

    private val used = mutableListOf<Fixture>()

    /** The detached tables of a unit are standalone tables until dropped: the shared schema is left as found. */
    @AfterEach
    fun dropDetachedTables() {
        superuser { c ->
            used.flatMap { listOf(it.t1, it.t2) }.forEach { tenant ->
                c
                    .prepareStatement(
                        "select 'drop table evidence.' || m.partition_table " +
                            "from evidence.retention_partition_member m join pg_class t " +
                            "on t.relname = m.partition_table and t.relnamespace = 'evidence'::regnamespace " +
                            "where m.tenant_id = ? and not t.relispartition",
                    ).use { st ->
                        st.setObject(1, tenant)
                        val statements = column(st.executeQuery())
                        c.createStatement().use { s -> statements.forEach { s.execute(it) } }
                    }
            }
        }
        used.clear()
    }

    private fun column(rs: java.sql.ResultSet): List<String> =
        rs.use { generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }

    private fun fixture(): Fixture =
        Fixture().also {
            used.add(it)
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
                .prepareStatement(
                    "select count(*) from evidence.$table where tenant_id = ? and entity_ref = ? and period_ref = ?",
                ).use {
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
                    "select content_hash from evidence.$table " +
                        "where tenant_id = ? and entity_ref = ? and period_ref = ? order by seq desc limit 1",
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
            "insert into evidence.legal_hold " +
                "(tenant_id, entity_ref, period_ref, reason, status, placed_at, placed_by) " +
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

    private fun part(
        key: Key,
        table: String,
    ) = "evidence.${table}_${partitionId(key)}"

    private fun detach(
        key: Key,
        table: String,
        retentionEnd: Instant?,
        audit: String? = "audit-1",
    ): String? =
        runtimeStatement(
            key.tenant,
            "select evidence.detach_expired_partition(cast(? as regclass), cast(? as regclass), ?, ?)",
            "evidence.$table",
            part(key, table),
            audit,
            retentionEnd?.let { java.sql.Timestamp.from(it) },
        )

    /** The four detach calls of the use case, one per chain table; the SQLSTATE of each (null when it succeeded). */
    private fun detachAll(
        key: Key,
        retentionEnd: Instant?,
        audit: String? = "audit-1",
    ): List<String?> = tables.map { detach(key, it, retentionEnd, audit) }

    private fun drop(
        key: Key,
        table: String,
    ): String? =
        runtimeStatement(key.tenant, "select evidence.drop_detached_partition(cast(? as regclass))", part(key, table))

    private fun archive(key: Key): String? =
        runtimeStatement(key.tenant, "select evidence.mark_partition_archived(?, 'vol-1')", partitionId(key))

    private fun assertRefused(
        key: Key,
        before: Map<String, Int>,
        sqlStates: List<String?>,
    ) {
        sqlStates.size shouldBe tables.size
        sqlStates.all { it != null } shouldBe true
        state(partitionId(key)) shouldBe "ATTACHED"
        counts(key) shouldBe before
    }

    private fun create(
        table: String,
        to: String,
    ) = "select evidence.create_retention_partition(cast('evidence.$table' as regclass), ?, 'E1', 'P9', $to)"

    private fun scalar(
        sql: String,
        vararg args: Any?,
    ): String? =
        superuser { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Test
    fun `INV-AC-22962 each chain table is range partitioned, one registry row per tenant entity and period`() {
        val f = fixture()
        superuser { c ->
            c
                .prepareStatement(
                    "select c.relname from pg_partitioned_table p join pg_class c on c.oid = p.partrelid " +
                        "join pg_namespace n on n.oid = c.relnamespace " +
                        "where n.nspname = 'evidence' and p.partstrat = 'r'",
                ).use { st ->
                    st.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() shouldBe tables.toSet()
                    }
                }
        }
        listOf(f.p1, f.p2, f.other).forEach { key ->
            scalar(
                "select count(*) from evidence.retention_partition " +
                    "where tenant_id = ? and entity_ref = ? and period_from = ? and period_to = ?",
                key.tenant,
                key.entity,
                key.period,
                key.period + "\u0001",
            ) shouldBe "1"
            partitionId(key).startsWith("rp_") shouldBe true
            scalar(
                "select count(*) from evidence.retention_partition_member where partition_id = ?",
                partitionId(key),
            ) shouldBe "4"
        }
        // the two appends of (T1, E1, P1) reused one unit
        scalar("select count(*) from evidence.retention_partition where tenant_id = ?", f.t1) shouldBe "2"
    }

    @Test
    fun `INV-AC-22962 the periods P1 and P10 are different units and never overlap`() {
        val f = fixture()
        val p10 = Key(f.t1, "E1", "P10")
        append(p10)
        (partitionId(p10) != partitionId(f.p1)) shouldBe true
        counts(f.p1) shouldBe tables.associateWith { 2 }
        counts(p10) shouldBe tables.associateWith { 1 }
    }

    @Test
    fun `INV-AC-22962 the detach removes only the rows of the partition and records the tail hash per chain table`() {
        val f = fixture()
        ready(f.p1)
        val id = partitionId(f.p1)
        val otherBefore = counts(f.p2) to counts(f.other)
        val tails = tables.associateWith { lastHash(it, f.p1) }
        counts(f.p1) shouldBe tables.associateWith { 2 }
        detachAll(f.p1, now.minusSeconds(DAY)) shouldBe tables.map { null }
        counts(f.p1) shouldBe tables.associateWith { 0 }
        (counts(f.p2) to counts(f.other)) shouldBe otherBefore
        state(id) shouldBe "DETACHED"
        tables.forEach { table ->
            scalar(
                "select tail_hashes ->> ? from evidence.retention_partition where partition_id = ?",
                table,
                id,
            ) shouldBe tails.getValue(table)
            scalar(
                "select tail_hash from evidence.retention_partition_member where partition_id = ? and chain_table = ?",
                id,
                table,
            ) shouldBe tails.getValue(table)
        }
    }

    @Test
    fun `INV-AC-22962 the drop before the detach is refused, and after the detach and the archive mark it succeeds`() {
        val f = fixture()
        ready(f.p1)
        val id = partitionId(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, tables.map { drop(f.p1, it) })
        detachAll(f.p1, now.minusSeconds(DAY)) shouldBe tables.map { null }
        // a detached, not yet archived partition is not dropped either
        tables.all { drop(f.p1, it) != null } shouldBe true
        state(id) shouldBe "DETACHED"
        archive(f.p1) shouldBe null
        state(id) shouldBe "ARCHIVED"
        tables.map { drop(f.p1, it) } shouldBe tables.map { null }
        state(id) shouldBe "DROPPED"
        scalar("select count(*) from pg_class where relname like ?", "%_$id") shouldBe "0"
    }

    @Test
    fun `INV-AC-22962 the archive mark of a unit with an attached member is refused`() {
        val f = fixture()
        ready(f.p1)
        detach(f.p1, tables.first(), now.minusSeconds(DAY)) shouldBe null
        (archive(f.p1) != null) shouldBe true
        state(partitionId(f.p1)) shouldBe "ATTACHED"
    }

    @Test
    fun `INV-AC-22962 the detach with an active hold is refused`() {
        val f = fixture()
        ready(f.p1)
        hold(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, detachAll(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with the notice window still open is refused`() {
        val f = fixture()
        notice(f.p1, now.plusSeconds(DAY))
        approve(f.p1, "u-one").shouldBeNull()
        approve(f.p1, "u-two").shouldBeNull()
        val before = counts(f.p1)
        assertRefused(f.p1, before, detachAll(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with no notice row is refused`() {
        val f = fixture()
        approve(f.p1, "u-one").shouldBeNull()
        approve(f.p1, "u-two").shouldBeNull()
        val before = counts(f.p1)
        assertRefused(f.p1, before, detachAll(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with a retention end later than now is refused`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, detachAll(f.p1, now.plusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with a null retention end is refused`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, detachAll(f.p1, null))
    }

    @Test
    fun `INV-AC-22962 the detach with one approval is refused`() {
        val f = fixture()
        notice(f.p1, now.minusSeconds(DAY))
        approve(f.p1, "u-one").shouldBeNull()
        val before = counts(f.p1)
        assertRefused(f.p1, before, detachAll(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach with the same approver twice is refused`() {
        val f = fixture()
        notice(f.p1, now.minusSeconds(DAY))
        approve(f.p1, "u-one").shouldBeNull()
        (approve(f.p1, "u-one") == "23505") shouldBe true
        val before = counts(f.p1)
        assertRefused(f.p1, before, detachAll(f.p1, now.minusSeconds(DAY)))
    }

    @Test
    fun `INV-AC-22962 the detach without an audit reference is refused`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        assertRefused(f.p1, before, detachAll(f.p1, now.minusSeconds(DAY), audit = " "))
    }

    @Test
    fun `INV-AC-22962 another tenant cannot detach the partition`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        val refused =
            tables.map { table ->
                runtimeStatement(
                    f.t2,
                    "select evidence.detach_expired_partition(cast(? as regclass), cast(? as regclass), 'a', ?)",
                    "evidence.$table",
                    part(f.p1, table),
                    java.sql.Timestamp.from(now.minusSeconds(DAY)),
                )
            }
        assertRefused(f.p1, before, refused)
    }

    @Test
    fun `INV-AC-22962 a detach naming a table that is not a partition of the parent is refused`() {
        val f = fixture()
        ready(f.p1)
        val before = counts(f.p1)
        val refused =
            tables.map { table ->
                runtimeStatement(
                    f.t1,
                    "select evidence.detach_expired_partition(cast(? as regclass), cast(? as regclass), 'a', ?)",
                    "evidence.$table",
                    "evidence.retention_partition",
                    java.sql.Timestamp.from(now.minusSeconds(DAY)),
                )
            } +
                runtimeStatement(
                    f.t1,
                    "select evidence.detach_expired_partition(cast('evidence.legal_hold' as regclass), " +
                        "cast(? as regclass), 'a', ?)",
                    part(f.p1, tables.first()),
                    java.sql.Timestamp.from(now.minusSeconds(DAY)),
                )
        refused.all { it != null } shouldBe true
        state(partitionId(f.p1)) shouldBe "ATTACHED"
        counts(f.p1) shouldBe before
    }

    @Test
    fun `INV-AC-22962 DELETE DROP TABLE and TRUNCATE by the runtime login are refused`() {
        val f = fixture()
        val before = counts(f.p1)
        tables.forEach { table ->
            runtimeStatement(f.t1, "delete from evidence.$table") shouldBe "42501"
            runtimeStatement(f.t1, "drop table evidence.$table") shouldBe "42501"
            runtimeStatement(f.t1, "truncate evidence.$table") shouldBe "42501"
            runtimeStatement(f.t1, "alter table evidence.$table detach partition ${part(f.p1, table)}") shouldBe "42501"
            runtimeStatement(f.t1, "drop table ${part(f.p1, table)}") shouldBe "42501"
            runtimeStatement(f.t1, "delete from ${part(f.p1, table)}") shouldBe "42501"
        }
        counts(f.p1) shouldBe before
    }

    @Test
    fun `INV-AC-22962 an append for a new period succeeds and lands in a partition of the same tenant`() {
        val f = fixture()
        val fresh = Key(f.t1, "E1", "P3")
        append(fresh)
        val id = partitionId(fresh)
        tables.forEach { table ->
            scalar(
                "select tableoid::regclass::text from evidence.$table where tenant_id = ? and period_ref = 'P3'",
                f.t1,
            ) shouldBe "evidence.${table}_$id"
        }
        scalar("select tenant_id::text from evidence.retention_partition where partition_id = ?", id) shouldBe
            f.t1.toString()
        // a tenant never creates a partition for another tenant
        runtimeStatement(
            f.t2,
            create("decision_trace", "'P9' || chr(1)"),
            f.t1,
        ) shouldBe "42501"
        // the creation names a chain table and one period only
        runtimeStatement(
            f.t1,
            create("legal_hold", "'P9' || chr(1)"),
            f.t1,
        ) shouldBe "22023"
        runtimeStatement(
            f.t1,
            create("audit_log", "'P99'"),
            f.t1,
        ) shouldBe "22023"
    }

    @Test
    fun `INV-AC-22962 the functions are security definer with a fixed search_path and the owners the lint allows`() {
        val owners =
            mapOf(
                "create_retention_partition" to EvidenceDb.MIGRATE,
                "detach_expired_partition" to EvidenceDb.MIGRATE,
                "drop_detached_partition" to EvidenceDb.MIGRATE,
                "mark_partition_archived" to EvidenceDb.SYS,
            )
        superuser { c ->
            c
                .prepareStatement(
                    "select p.proname, p.prosecdef, p.proconfig::text, pg_get_userbyid(p.proowner), " +
                        "has_function_privilege('evidence_runtime', p.oid, 'execute'), " +
                        "has_function_privilege('public', p.oid, 'execute') " +
                        "from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                        "where n.nspname = 'evidence' and p.proname = any (?)",
                ).use { st ->
                    st.setArray(1, c.createArrayOf("text", owners.keys.toTypedArray()))
                    st.executeQuery().use { rs ->
                        var seen = 0
                        while (rs.next()) {
                            seen++
                            rs.getBoolean(2) shouldBe true
                            rs.getString(3)!!.contains("search_path=pg_catalog, evidence, pg_temp") shouldBe true
                            rs.getString(4) shouldBe owners.getValue(rs.getString(1))
                            rs.getBoolean(5) shouldBe true
                            rs.getBoolean(6) shouldBe false
                        }
                        seen shouldBe owners.size
                    }
                }
        }
    }

    @Test
    fun `INV-AC-22962 rows seeded in each chain table before the migration are present after it`() {
        val seeds = listOf(UUID.randomUUID() to "P1", UUID.randomUUID() to "P2")
        val name = "scratch_" + UUID.randomUUID().toString().replace("-", "")
        EvidenceDb.superuser().use { it.createStatement().use { s -> s.execute("create database $name") } }
        val url = EvidenceDb.jdbcUrl.substringBeforeLast('/') + "/$name"
        try {
            EvidenceDb.superuserOf(url).use { c ->
                val provision = checkNotNull(javaClass.getResource("/db/provision.sql")).readText()
                c.createStatement().use { it.execute(provision) }
            }
            flyway(url, "20").migrate()
            EvidenceDb.superuserOf(url).use { c -> seeds.forEach { (tenant, period) -> seedRows(c, tenant, period) } }
            flyway(url, null).migrate()
            EvidenceDb.superuserOf(url).use { c -> seeds.forEach { (tenant, period) -> assertRows(c, tenant, period) } }
        } finally {
            EvidenceDb.superuser().use {
                it.createStatement().use { s -> s.execute("drop database if exists $name with (force)") }
            }
        }
    }

    private fun seedRows(
        c: Connection,
        tenant: UUID,
        period: String,
    ) = tables.forEach { table ->
        repeat(2) { n ->
            c
                .prepareStatement(
                    "insert into evidence.$table (id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                        "content, content_hash, recorded_at) values (?, ?, 'E1', ?, ?, 'T', ?, ?, now())",
                ).use { st ->
                    st.setObject(1, UUID.randomUUID())
                    st.setObject(2, tenant)
                    st.setString(3, period)
                    st.setLong(4, n + 1L)
                    st.setBytes(5, byteArrayOf(n.toByte()))
                    st.setString(6, "h-$table-$period-$n")
                    st.execute()
                }
        }
    }

    private fun column(
        c: Connection,
        sql: String,
        tenant: UUID? = null,
    ): List<String> =
        c.prepareStatement(sql).use { st ->
            if (tenant != null) st.setObject(1, tenant)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    private fun assertRows(
        c: Connection,
        tenant: UUID,
        period: String,
    ) {
        tables.forEach { table ->
            column(c, "select content_hash from evidence.$table where tenant_id = ? order by seq", tenant) shouldBe
                listOf("h-$table-$period-0", "h-$table-$period-1")
        }
        column(c, "select count(*) from evidence.retention_partition where tenant_id = ?", tenant) shouldBe listOf("1")
        column(c, "select relname from pg_class where relname like '%legacy%'") shouldBe emptyList()
    }

    private fun flyway(
        url: String,
        target: String?,
    ) = org.flywaydb.core.Flyway
        .configure()
        .dataSource(url, EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD)
        .schemas(EvidenceDb.SCHEMA)
        .defaultSchema(EvidenceDb.SCHEMA)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .apply { if (target != null) target(target) }
        .load()

    private companion object {
        const val DAY = 86_400L
    }
}
