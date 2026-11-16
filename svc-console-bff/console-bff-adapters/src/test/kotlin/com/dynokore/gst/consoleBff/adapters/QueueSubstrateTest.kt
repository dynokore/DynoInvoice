package com.dynokore.gst.consoleBff.adapters

import com.dynokore.platform.outboxandqueue.QueueSchema
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * The queue tables and the claim function of the library in `console_bff`, with forced tenant isolation on the queue
 * (INV-AC-22009; PostgreSQL forced row level security, least privilege, NIST SP 800-53 AC-6).
 */
class QueueSubstrateTest {
    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        ConsoleDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
        }

    private val claimDue = "console_bff.claim_due(text, text, timestamptz, timestamptz)"

    @Test
    fun `INV-AC-22009 the tables work_queue and lane_rate_window are present`() {
        val tables =
            query(
                "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                    "where n.nspname = 'console_bff' and c.relkind = 'r' order by 1",
            ) { it.getString(1) }
        tables shouldContainAll listOf("work_queue", "lane_rate_window")
    }

    @Test
    fun `INV-AC-22009 claim_due exists, is owned by the sys role and is security definer`() {
        val rows =
            query(
                "select r.rolname, p.prosecdef from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                    "join pg_roles r on r.oid = p.proowner " +
                    "where n.nspname = 'console_bff' and p.proname = 'claim_due' " +
                    "and p.oid = '$claimDue'::regprocedure",
            ) { it.getString(1) to it.getBoolean(2) }
        rows shouldBe listOf(ConsoleDb.SYS to true)
    }

    @Test
    fun `INV-AC-22009 claim_due has no PUBLIC grant and the runtime role may execute it`() {
        val acl =
            query(
                "select (select count(*) from aclexplode(p.proacl) a where a.grantee = 0), " +
                    "has_function_privilege('${ConsoleDb.RUNTIME}', p.oid, 'EXECUTE'), p.proacl is null " +
                    "from pg_proc p where p.oid = '$claimDue'::regprocedure",
            ) { Triple(it.getInt(1), it.getBoolean(2), it.getBoolean(3)) }
        acl shouldBe listOf(Triple(0, true, false))
    }

    @Test
    fun `INV-AC-22009 work_queue has row level security enabled and forced`() {
        val flags =
            query(
                "select c.relrowsecurity, c.relforcerowsecurity from pg_class c " +
                    "join pg_namespace n on n.oid = c.relnamespace " +
                    "where n.nspname = 'console_bff' and c.relname = 'work_queue'",
            ) { it.getBoolean(1) to it.getBoolean(2) }
        flags shouldBe listOf(true to true)
    }

    @Test
    fun `INV-AC-22009 work_queue has a policy that names console_bff bound_tenant`() {
        val named =
            query(
                "select count(*) from pg_policies where schemaname = 'console_bff' and tablename = 'work_queue' " +
                    "and (coalesce(qual, '') like '%console_bff.bound_tenant()%' " +
                    "or coalesce(with_check, '') like '%console_bff.bound_tenant()%')",
            ) { it.getInt(1) }
        (named.single() > 0) shouldBe true
    }

    @Test
    fun `INV-AC-22009 lane_rate_window is platform scoped and has no tenant_id column`() {
        val columns =
            query(
                "select count(*) from pg_attribute a join pg_class c on c.oid = a.attrelid " +
                    "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'console_bff' " +
                    "and c.relname = 'lane_rate_window' and a.attname = 'tenant_id' and not a.attisdropped",
            ) { it.getInt(1) }
        columns shouldBe listOf(0)
    }

    @Test
    fun `INV-AC-22009 the first work_queue migration file is unchanged`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream(FIRST)).use { it.readBytes() }
        val hex =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(java.util.Locale.ROOT, it)
            }
        hex shouldBe "a74f6f88534b68ff7310e5910324cca5288b75c317b54037c735cb237e2fe674"
    }

    @Test
    fun `INV-AC-23707 the drain order migration section equals the generator output`() {
        val text = checkNotNull(javaClass.getResource(DRAIN)).readText()
        val section =
            text
                .substringAfter("$BEGIN\n", "")
                .substringBefore("\n-- END QueueSchema.drainOrderUpgradeDdl", "")
        section shouldBe QueueSchema.drainOrderUpgradeDdl(ConsoleDb.SCHEMA, ConsoleDb.RUNTIME, ConsoleDb.SYS)
    }

    @Test
    fun `INV-AC-23707 drain_tier and drain_rank have the expected shape and the sys role may select them`() {
        val cols =
            query(
                "select column_name, data_type, is_nullable, column_default, " +
                    "has_column_privilege('${ConsoleDb.SYS}', 'console_bff.work_queue', column_name, 'SELECT') " +
                    "from information_schema.columns where table_schema = 'console_bff' " +
                    "and table_name = 'work_queue' and column_name in ('drain_tier', 'drain_rank') " +
                    "order by column_name",
            ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getBoolean(5)) }
        cols shouldBe
            listOf(
                listOf("drain_rank", "integer", "YES", null, true),
                listOf("drain_tier", "smallint", "NO", "3", true),
            )
    }

    @Test
    fun `INV-AC-23707 claim_due orders by drain tier, drain rank nulls last, then seq`() {
        val def = query("select pg_get_functiondef('$claimDue'::regprocedure)") { it.getString(1) }.single()
        def shouldContain "order by q.drain_tier, q.drain_rank nulls last, q.seq"
    }

    private companion object {
        const val FIRST = "/db/migration/V202611141200__console_bff_work_queue.sql"
        const val BEGIN =
            "-- BEGIN QueueSchema.drainOrderUpgradeDdl(\"console_bff\", \"console_bff_runtime\", \"console_bff_sys\")"
        const val DRAIN = "/db/migration/V202611161500__console_bff_work_queue_drain_order.sql"
    }
}
