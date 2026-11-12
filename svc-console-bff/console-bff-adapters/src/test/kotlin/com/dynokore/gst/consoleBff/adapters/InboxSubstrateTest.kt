package com.dynokore.gst.consoleBff.adapters

import com.dynokore.platform.outboxandqueue.OutboxSchema
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/**
 * The sys role, the signed-binding functions and the inbox tables of the library in `console_bff`, with forced tenant
 * isolation (INV-AC-21022; PostgreSQL forced row level security, least privilege, NIST SP 800-53 AC-6).
 */
class InboxSubstrateTest {
    private val migration =
        checkNotNull(javaClass.getResource("/db/migration/V202611121000__console_bff_inbox_substrate.sql")).readText()

    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        ConsoleDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
        }

    @Test
    fun `INV-AC-21022 bound_tenant exists, is owned by the sys role and is security definer`() {
        val rows =
            query(
                "select r.rolname, p.prosecdef from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                    "join pg_roles r on r.oid = p.proowner " +
                    "where n.nspname = 'console_bff' and p.proname = 'bound_tenant'",
            ) { it.getString(1) to it.getBoolean(2) }
        rows shouldBe listOf(ConsoleDb.SYS to true)
    }

    @Test
    fun `INV-AC-21022 the runtime role holds no privilege on binding_key`() {
        val rows =
            query(
                "select has_table_privilege('${ConsoleDb.RUNTIME}', 'console_bff.binding_key', " +
                    "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'), " +
                    "has_any_column_privilege('${ConsoleDb.RUNTIME}', 'console_bff.binding_key', " +
                    "'SELECT,INSERT,UPDATE,REFERENCES')",
            ) { it.getBoolean(1) to it.getBoolean(2) }
        rows shouldBe listOf(false to false)
    }

    @Test
    fun `INV-AC-21022 the five inbox-side tables are present`() {
        tables() shouldContainAll
            listOf("inbox", "consumer_sequence", "event_dead_letter", "consumer_offset", "consumer_source_seen")
    }

    @Test
    fun `INV-AC-21022 every table with tenant_id has row level security enabled and forced`() {
        val tenantTables = tenantTables()
        tenantTables.shouldNotBeEmpty()
        tenantTables shouldContainAll listOf("inbox", "consumer_sequence", "event_dead_letter")
        val flags =
            query(
                "select c.relname, c.relrowsecurity, c.relforcerowsecurity from pg_class c " +
                    "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'console_bff' and c.relkind = 'r'",
            ) { Triple(it.getString(1), it.getBoolean(2), it.getBoolean(3)) }
        tenantTables.forEach { t -> flags.single { it.first == t } shouldBe Triple(t, true, true) }
    }

    @Test
    fun `INV-AC-21022 every table with tenant_id has a policy that names console_bff bound_tenant`() {
        tenantTables().forEach { t ->
            val named =
                query(
                    "select count(*) from pg_policies where schemaname = 'console_bff' and tablename = '$t' " +
                        "and (coalesce(qual, '') like '%console_bff.bound_tenant()%' " +
                        "or coalesce(with_check, '') like '%console_bff.bound_tenant()%')",
                ) { it.getInt(1) }
            (named.single() > 0) shouldBe true
        }
    }

    @Test
    fun `INV-AC-21022 each tenant reads exactly its own inbox row and no binding reads none`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val consumer = "inbox-substrate-" + UUID.randomUUID()
        try {
            insertAs(a, consumer, "event-a")
            insertAs(b, consumer, "event-b")
            readAs(a, consumer) shouldBe listOf("event-a")
            readAs(b, consumer) shouldBe listOf("event-b")
            readAs(null, consumer) shouldBe emptyList()
        } finally {
            ConsoleDb.superuser().use { c ->
                c.prepareStatement("delete from console_bff.inbox where consumer = ?").use {
                    it.setString(1, consumer)
                    it.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `INV-AC-21022 the migration section equals the generator output`() {
        val section =
            migration
                .substringAfter(
                    "-- BEGIN OutboxSchema.ddl(\"console_bff\", \"console_bff_runtime\", \"console_bff_sys\")\n",
                ).substringBefore("\n-- END OutboxSchema.ddl")
        section shouldBe OutboxSchema.ddl(ConsoleDb.SCHEMA, ConsoleDb.RUNTIME, ConsoleDb.SYS)
    }

    private fun tables(): List<String> =
        query(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'console_bff' and c.relkind = 'r' order by 1",
        ) { it.getString(1) }

    private fun tenantTables(): List<String> =
        query(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped " +
                "where n.nspname = 'console_bff' and c.relkind = 'r' order by 1",
        ) { it.getString(1) }

    private fun runtime(block: (Connection) -> Unit) =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            try {
                block(c)
            } finally {
                c.rollback()
            }
        }

    private fun insertAs(
        tenant: UUID,
        consumer: String,
        eventId: String,
    ) = ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
        c.autoCommit = false
        SignedBindings.bind(c, tenant)
        c.prepareStatement("insert into console_bff.inbox (consumer, event_id, tenant_id) values (?, ?, ?)").use {
            it.setString(1, consumer)
            it.setString(2, eventId)
            it.setObject(3, tenant)
            it.executeUpdate()
        }
        c.commit()
    }

    private fun readAs(
        tenant: UUID?,
        consumer: String,
    ): List<String> {
        var rows = emptyList<String>()
        runtime { c ->
            if (tenant != null) SignedBindings.bind(c, tenant)
            val sql = "select event_id from console_bff.inbox where consumer = ? order by event_id"
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, consumer)
                ps.executeQuery().use { rs -> rows = buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        return rows
    }
}
