package com.dynokore.gst.consoleBff.adapters

import com.dynokore.platform.configresolver.ConfigSchema
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/**
 * The config-resolver tables, row policies, grants and functions of the pinned module in `console_bff`; the migration
 * file equals the generated OUTBOX text (INV-AC-25570; TPL-12, forced row level security, least privilege,
 * NIST SP 800-53 AC-6).
 */
class ConfigResolverMigrationTest {
    private val resource = "/db/migration/V202611201100__create_config_resolver.sql"

    private fun body(): String =
        checkNotNull(javaClass.getResource(resource))
            .readText()
            .lines()
            .dropWhile { it.startsWith("--") || it.isBlank() }
            .joinToString("\n")
            .trim()

    private fun generated(
        schema: String,
        mode: ConfigSchema.EventMode,
    ) = ConfigSchema.ddl(schema, ConsoleDb.RUNTIME, ConsoleDb.SYS, mode).trim()

    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        ConsoleDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
        }

    private fun privilege(
        role: String,
        table: String,
        privilege: String,
    ): Boolean =
        query("select has_table_privilege('$role', 'console_bff.$table', '$privilege')") { it.getBoolean(1) }.single()

    @Test
    fun `INV-AC-25570 the file body equals the generated OUTBOX text and no look-alike`() {
        body() shouldBe generated(ConsoleDb.SCHEMA, ConfigSchema.EventMode.OUTBOX)
        body() shouldNotBe generated(ConsoleDb.SCHEMA, ConfigSchema.EventMode.NO_OUTBOX)
        body() shouldNotBe generated("console_bff2", ConfigSchema.EventMode.OUTBOX)
        body().contains("create schema", ignoreCase = true) shouldBe false
    }

    @Test
    fun `INV-AC-25570 the config tables are exactly the four generated ones`() {
        query(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'console_bff' and c.relkind = 'r' and c.relname like 'config\\_%' order by 1",
        ) { it.getString(1) }.toSet() shouldBe
            setOf(
                ConfigSchema.SETTING,
                ConfigSchema.INSTALLATION_SETTING,
                ConfigSchema.RESOLVED_VALUE,
                ConfigSchema.RELAXATION_ACKNOWLEDGEMENT,
            ).also {
                it shouldBe
                    setOf(
                        "config_setting",
                        "config_installation_setting",
                        "config_resolved_value",
                        "config_relaxation_acknowledgement",
                    )
            }
        query(
            "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'console_bff' and c.relname in ('setting', 'installation_setting')",
        ) { it.getInt(1) } shouldBe listOf(0)
    }

    @Test
    fun `INV-AC-25570 row level security is forced on exactly the three tenant tables`() {
        query(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'console_bff' and c.relkind = 'r' and c.relname like 'config\\_%' " +
                "and c.relrowsecurity and c.relforcerowsecurity order by 1",
        ) { it.getString(1) }.toSet() shouldBe
            setOf("config_setting", "config_resolved_value", "config_relaxation_acknowledgement")
        query(
            "select count(*) from information_schema.columns where table_schema = 'console_bff' " +
                "and table_name = 'config_installation_setting' and column_name = 'tenant_id'",
        ) { it.getInt(1) } shouldBe listOf(0)
    }

    @Test
    fun `INV-AC-25570 the runtime role holds least privilege and the sys role writes installation settings`() {
        privilege(ConsoleDb.RUNTIME, "config_setting", "SELECT") shouldBe true
        privilege(ConsoleDb.RUNTIME, "config_setting", "INSERT") shouldBe true
        privilege(ConsoleDb.RUNTIME, "config_setting", "DELETE") shouldBe false
        privilege(ConsoleDb.RUNTIME, "config_installation_setting", "SELECT") shouldBe true
        privilege(ConsoleDb.RUNTIME, "config_installation_setting", "INSERT") shouldBe false
        listOf(
            "config_setting",
            "config_installation_setting",
            "config_resolved_value",
            "config_relaxation_acknowledgement",
        ).forEach { t ->
            privilege(ConsoleDb.RUNTIME, t, "UPDATE") shouldBe false
            privilege(ConsoleDb.RUNTIME, t, "DELETE") shouldBe false
        }
        privilege(ConsoleDb.SYS, "config_installation_setting", "INSERT") shouldBe true
    }

    @Test
    fun `INV-AC-25570 the five functions exist, are owned by the sys role and the mode is OUTBOX`() {
        val owners =
            query(
                "select p.proname, r.rolname from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                    "join pg_roles r on r.oid = p.proowner where n.nspname = 'console_bff' and p.proname in " +
                    "('write_installation_setting', 'config_purge_subject', 'config_purge_tenant', " +
                    "'config_option_value_maximum', 'config_installation_mode')",
            ) { it.getString(1) to it.getString(2) }
        owners.toSet() shouldBe
            setOf(
                "write_installation_setting",
                "config_purge_subject",
                "config_purge_tenant",
                "config_option_value_maximum",
                "config_installation_mode",
            ).map { it to ConsoleDb.SYS }.toSet()
        owners.size shouldBe 5
        query("select console_bff.config_installation_mode()") { it.getString(1) } shouldBe listOf("OUTBOX")
    }

    @Test
    fun `INV-AC-25570 a config_setting row of tenant A is read under A and not under B`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        try {
            insertAs(a)
            readAs(a, a) shouldBe 1
            readAs(b, a) shouldBe 0
        } finally {
            ConsoleDb.superuser().use { c ->
                c.prepareStatement("delete from console_bff.config_setting where tenant_id = ?").use {
                    it.setObject(1, a)
                    it.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `INV-AC-25607 provision_sql grants no default table privilege`() {
        val provision = checkNotNull(javaClass.getResource("/db/provision.sql")).readText()
        val form = Regex("alter\\s+default\\s+privileges[^;]*\\son\\s+tables", RegexOption.IGNORE_CASE)
        form.containsMatchIn(provision) shouldBe false
        form.containsMatchIn(
            "alter default privileges for role console_bff_migrate in schema console_bff grant select, insert, " +
                "update, delete on tables to console_bff_runtime;",
        ) shouldBe true
        query(
            "select count(*) from pg_default_acl d join pg_namespace n on n.oid = d.defaclnamespace " +
                "where n.nspname = 'console_bff' and d.defaclobjtype = 'r' " +
                "and d.defaclacl::text like '%${ConsoleDb.RUNTIME}=%'",
        ) { it.getInt(1) } shouldBe listOf(0)
    }

    @Test
    fun `INV-AC-25607 console_session runtime privileges`() {
        val file = "/db/migration/V202611201110__console_session_runtime_grants.sql"
        checkNotNull(javaClass.getResource(file))
            .readText()
            .lines()
            .dropWhile { it.startsWith("--") || it.isBlank() }
            .joinToString("\n")
            .trim() shouldBe
            "grant select, insert, update, delete on console_bff.console_session to console_bff_runtime;"
        listOf("SELECT", "INSERT", "UPDATE", "DELETE").forEach {
            privilege(ConsoleDb.RUNTIME, "console_session", it) shouldBe true
        }
        listOf("TRUNCATE", "REFERENCES", "TRIGGER").forEach {
            privilege(ConsoleDb.RUNTIME, "console_session", it) shouldBe false
        }
    }

    private fun insertAs(tenant: UUID) =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            c
                .prepareStatement(
                    "insert into console_bff.config_setting (id, tenant_id, vp_key, scope_level, scope_id, " +
                        "option_keys, option_values, source, effective_from, set_by, reason, recorded_at) " +
                        "values (?, ?, 'VP-84', 'TENANT', ?, array['idle'], '{\"idle\": 10}'::jsonb, 'OVERRIDE', " +
                        "current_date, 'user-1', 'shorter idle time', now())",
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, tenant)
                    it.setString(3, tenant.toString())
                    it.executeUpdate()
                }
            c.commit()
        }

    private fun readAs(
        binding: UUID,
        tenant: UUID,
    ): Int =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c: Connection ->
            c.autoCommit = false
            try {
                SignedBindings.bind(c, binding)
                c.prepareStatement("select count(*) from console_bff.config_setting where tenant_id = ?").use { ps ->
                    ps.setObject(1, tenant)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            } finally {
                c.rollback()
            }
        }
}
