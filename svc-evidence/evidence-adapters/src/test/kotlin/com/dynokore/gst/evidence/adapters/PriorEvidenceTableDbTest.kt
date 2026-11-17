package com.dynokore.gst.evidence.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-24306 (V24): evidence.prior_evidence is an append-only range-partitioned table and the fifth member of each
 * retention unit; the retention functions detach, archive and drop it with the unit. Every case uses its own random
 * tenants, so the cases do not see each other's rows.
 */
class PriorEvidenceTableDbTest {
    private val chain = listOf("decision_trace", "evidence_payload", "acknowledgement", "audit_log")
    private val all = chain + "prior_evidence"
    private val tenants = mutableListOf<UUID>()

    private class Unit(
        val tenant: UUID,
        val entity: String = "E1",
        val period: String = "2026-Q1",
    )

    private fun unit(): Unit = Unit(UUID.randomUUID()).also { tenants.add(it.tenant) }

    /** Detached tables are standalone until dropped: the shared schema is left as found. */
    @AfterEach
    fun dropDetachedTables() {
        EvidenceDb.superuser().use { c ->
            tenants.forEach { tenant ->
                val statements =
                    c
                        .prepareStatement(
                            "select 'drop table evidence.' || m.partition_table " +
                                "from evidence.retention_partition_member m " +
                                "join pg_class t on t.relname = m.partition_table " +
                                "and t.relnamespace = 'evidence'::regnamespace " +
                                "where m.tenant_id = ? and not t.relispartition",
                        ).use { st ->
                            st.setObject(1, tenant)
                            st.executeQuery().use { rs ->
                                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                            }
                        }
                c.createStatement().use { s -> statements.forEach { s.execute(it) } }
            }
        }
        tenants.clear()
    }

    private fun <T> superuser(block: (Connection) -> T): T = EvidenceDb.superuser().use(block)

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

    /** One statement as the runtime login with the signed binding of [tenant]: the SQLSTATE of a failure, else null. */
    private fun runtime(
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

    private fun runtimeScalar(
        tenant: UUID,
        sql: String,
    ): String? =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            c.prepareStatement(sql).use { st ->
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun create(
        u: Unit,
        table: String,
    ): String? =
        runtime(
            u.tenant,
            "select evidence.create_retention_partition(cast(? as regclass), ?, ?, ?, ?)",
            "evidence.$table",
            u.tenant,
            u.entity,
            u.period,
            u.period + "\u0001",
        )

    private fun partitionId(u: Unit): String =
        scalar(
            "select partition_id from evidence.retention_partition " +
                "where tenant_id = ? and entity_ref = ? and period_from = ?",
            u.tenant,
            u.entity,
            u.period,
        )!!

    private fun part(
        u: Unit,
        table: String,
    ) = "evidence.${table}_${partitionId(u)}"

    private fun insertPrior(
        u: Unit,
        ack: String = "ACK-LEGACY-0001",
    ): String? =
        runtime(
            u.tenant,
            "insert into evidence.prior_evidence (id, tenant_id, entity_ref, period_ref, document_number, " +
                "transaction_date, acknowledgement_ref, prior_solution, imported_at, imported_by) " +
                "values (?, ?, ?, ?, 'INV-100', date '2026-02-27', ?, 'LegacyInvoiceNow', now(), 'u-importer')",
            UUID.randomUUID(),
            u.tenant,
            u.entity,
            u.period,
            ack,
        )

    /** A unit with a partition for each of [tables] and, when prior, one imported row. */
    private fun built(tables: List<String>): Unit =
        unit().also { u ->
            tables.forEach { create(u, it) shouldBe null }
            if ("prior_evidence" in tables) insertPrior(u) shouldBe null
        }

    private fun ready(u: Unit) {
        val id = partitionId(u)
        runtime(
            u.tenant,
            "insert into evidence.retention_notice values (?, ?, now(), 30, ?)",
            u.tenant,
            id,
            Timestamp.from(Instant.now().minusSeconds(DAY)),
        ) shouldBe null
        listOf("u-one", "u-two").forEach {
            runtime(
                u.tenant,
                "insert into evidence.retention_approval values (?, ?, ?, 'retention.delete.approve', now())",
                u.tenant,
                id,
                it,
            ) shouldBe null
        }
    }

    private fun detach(
        u: Unit,
        table: String,
    ): String? =
        runtime(
            u.tenant,
            "select evidence.detach_expired_partition(cast(? as regclass), cast(? as regclass), 'audit-1', ?)",
            "evidence.$table",
            part(u, table),
            Timestamp.from(Instant.now().minusSeconds(DAY)),
        )

    private fun archive(u: Unit): String? =
        runtime(u.tenant, "select evidence.mark_partition_archived(?, 'vol-1')", partitionId(u))

    private fun drop(
        u: Unit,
        table: String,
    ): String? = runtime(u.tenant, "select evidence.drop_detached_partition(cast(? as regclass))", part(u, table))

    private fun unitState(u: Unit) =
        scalar("select state from evidence.retention_partition where partition_id = ?", partitionId(u))

    private fun memberState(
        u: Unit,
        table: String,
    ) = scalar(
        "select state from evidence.retention_partition_member where partition_id = ? and chain_table = ?",
        partitionId(u),
        table,
    )

    @Test
    fun `INV-AC-24306 the prior table is range partitioned on tenant, entity and period`() {
        scalar(
            "select p.partstrat::text || ':' || c.relkind::text || ':' || " +
                "(select string_agg(a.attname, ',' order by k.ord) " +
                "from unnest(p.partattrs::int2[]) with ordinality k(attnum, ord) " +
                "join pg_attribute a on a.attrelid = p.partrelid and a.attnum = k.attnum) " +
                "from pg_partitioned_table p join pg_class c on c.oid = p.partrelid " +
                "where p.partrelid = 'evidence.prior_evidence'::regclass",
        ) shouldBe "r:p:tenant_id,entity_ref,period_ref"
    }

    @Test
    fun `INV-AC-24306 the prior member is registered and the creation call repeated returns the same id`() {
        val u = unit()
        create(u, "prior_evidence") shouldBe null
        val id = partitionId(u)
        memberState(u, "prior_evidence") shouldBe "ATTACHED"
        runtimeScalar(
            u.tenant,
            "select evidence.create_retention_partition('evidence.prior_evidence'::regclass, '${u.tenant}', " +
                "'${u.entity}', '${u.period}', '${u.period}' || chr(1))",
        ) shouldBe id
        scalar(
            "select count(*) from evidence.retention_partition_member " +
                "where partition_id = ? and chain_table = 'prior_evidence'",
            id,
        ) shouldBe "1"
    }

    @Test
    fun `INV-AC-24306 the runtime login inserts and reads but is refused UPDATE, DELETE and TRUNCATE`() {
        val u = built(listOf("prior_evidence"))
        runtime(u.tenant, "update evidence.prior_evidence set prior_solution = 'x'") shouldBe "42501"
        runtime(u.tenant, "delete from evidence.prior_evidence") shouldBe "42501"
        runtime(u.tenant, "truncate evidence.prior_evidence") shouldBe "42501"
        runtimeScalar(u.tenant, "select count(*) from evidence.prior_evidence") shouldBe "1"
    }

    @Test
    fun `INV-AC-24306 row level security is forced and a tenant reads no row of another tenant`() {
        val t1 = built(listOf("prior_evidence"))
        val t2 = built(listOf("prior_evidence"))
        scalar(
            "select relrowsecurity::text || relforcerowsecurity::text from pg_class " +
                "where oid = 'evidence.prior_evidence'::regclass",
        ) shouldBe "truetrue"
        runtimeScalar(
            t2.tenant,
            "select count(*) from evidence.prior_evidence where tenant_id = '${t1.tenant}'",
        ) shouldBe "0"
        runtimeScalar(t2.tenant, "select count(*) from evidence.prior_evidence") shouldBe "1"
        runtime(
            t2.tenant,
            "insert into evidence.prior_evidence (id, tenant_id, entity_ref, period_ref, document_number, " +
                "transaction_date, acknowledgement_ref, prior_solution, imported_at, imported_by) " +
                "values (?, ?, 'E1', '2026-Q1', 'D', date '2026-01-01', 'A', 'S', now(), 'u')",
            UUID.randomUUID(),
            t1.tenant,
        ) shouldBe "42501"
    }

    @Test
    fun `INV-AC-24306 the check of chain_table admits the five tables and the detach records a null tail hash`() {
        val u = built(all)
        ready(u)
        detach(u, "prior_evidence") shouldBe null
        scalar(
            "select tail_hash from evidence.retention_partition_member " +
                "where partition_id = ? and chain_table = 'prior_evidence'",
            partitionId(u),
        ) shouldBe null
        memberState(u, "prior_evidence") shouldBe "DETACHED"
        unitState(u) shouldBe "ATTACHED"
        chain.forEach { detach(u, it) shouldBe null }
        unitState(u) shouldBe "DETACHED"
    }

    @Test
    fun `INV-AC-24306 the archive mark is refused while a member is not DETACHED`() {
        val u = built(all)
        ready(u)
        chain.forEach { detach(u, it) shouldBe null }
        unitState(u) shouldBe "ATTACHED"
        archive(u) shouldBe "55000"
        detach(u, "prior_evidence") shouldBe null
        archive(u) shouldBe null
        unitState(u) shouldBe "ARCHIVED"
    }

    @Test
    fun `INV-AC-24306 a unit with only the four chain members is archived as before`() {
        val u = built(chain)
        ready(u)
        chain.forEach { detach(u, it) shouldBe null }
        archive(u) shouldBe null
        unitState(u) shouldBe "ARCHIVED"
    }

    @Test
    fun `INV-AC-24306 a unit missing a chain member is not archived`() {
        val u = built(listOf("decision_trace", "evidence_payload", "acknowledgement", "prior_evidence"))
        ready(u)
        listOf("decision_trace", "evidence_payload", "acknowledgement", "prior_evidence").forEach {
            detach(u, it) shouldBe null
        }
        archive(u) shouldBe "55000"
    }

    @Test
    fun `INV-AC-24306 the drop of each member ends in DROPPED and the unit is DROPPED`() {
        val u = built(all)
        ready(u)
        all.forEach { detach(u, it) shouldBe null }
        drop(u, "prior_evidence") shouldBe "55000"
        archive(u) shouldBe null
        all.forEach { drop(u, it) shouldBe null }
        all.forEach { memberState(u, it) shouldBe "DROPPED" }
        unitState(u) shouldBe "DROPPED"
    }

    @Test
    fun `INV-AC-24306 a parent that is not one of the five tables is refused with 22023`() {
        val u = unit()
        listOf("decision_trace_legacy", "prior_evidence_x", "retention_partition").forEach { name ->
            runtime(
                u.tenant,
                "select evidence.create_retention_partition(cast(? as regclass), ?, 'E1', '2026-Q1', ?)",
                "evidence.$name",
                u.tenant,
                "2026-Q1\u0001",
            ) shouldBe if (name == "retention_partition") "22023" else "42P01"
        }
        // A look-alike that exists as a table is refused by the function itself (22023), not by name resolution.
        superuser { c -> c.createStatement().use { it.execute("create table evidence.prior_evidence_x (id int)") } }
        try {
            runtime(
                u.tenant,
                "select evidence.create_retention_partition(" +
                    "cast('evidence.prior_evidence_x' as regclass), ?, 'E1', '2026-Q1', ?)",
                u.tenant,
                "2026-Q1\u0001",
            ) shouldBe "22023"
        } finally {
            superuser { c -> c.createStatement().use { it.execute("drop table evidence.prior_evidence_x") } }
        }
    }

    private companion object {
        const val DAY = 86_400L
    }
}
