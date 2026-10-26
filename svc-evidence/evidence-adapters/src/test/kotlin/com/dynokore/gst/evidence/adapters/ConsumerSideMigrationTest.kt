package com.dynokore.gst.evidence.adapters

import com.dynokore.platform.outboxandqueue.OutboxSchema
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * INV-Q-10812 A: schema `evidence` built from V1 plus the consumer-side migration equals schema `evidence` built from
 * the output of `OutboxSchema.ddl` of the pinned platform-libs version, by name and definition: tables with their
 * columns, row-level-security flags, policies, indexes, views, functions with owner and ACL, triggers and the table
 * privileges. Both scratch databases hold the binding functions of the installer first (`db/provision.sql`).
 */
class ConsumerSideMigrationTest {
    private val generated: String get() = OutboxSchema.ddl(EvidenceDb.SCHEMA, EvidenceDb.RUNTIME, EvidenceDb.SYS)

    @Test
    fun `the files V1 and the consumer-side migration, executed in order, build the schema the generator builds`() {
        val fromGenerator = scratch { url -> viaGenerator(url, generated) }
        val fromMigrations = scratch { url -> viaFiles(url, "V1__evidence_schema.sql", CONSUMER_SIDE) }
        assertEqual(fromGenerator, fromMigrations)
    }

    /** INV-Q-10832 A: Flyway itself (the version of the build) builds the schema the generator builds, character for character. */
    @Test
    fun `V1 plus the consumer-side migration run by Flyway build the schema the generator builds`() {
        val fromGenerator = scratch { url -> viaGenerator(url, generated) }
        val fromMigrations = scratch { url -> viaMigrations(url, "V1__evidence_schema.sql", CONSUMER_SIDE) }
        assertEqual(fromGenerator, fromMigrations)
    }

    /** Flyway drops U+FEFF from a script it reads, so a script that holds one differs from the schema it builds. */
    @Test
    fun `the consumer-side migration holds no U+FEFF and equals itself without one`() {
        val text = resource(CONSUMER_SIDE)
        text.contains('\uFEFF') shouldBe false
        text.replace("\uFEFF", "") shouldBe text
    }

    /** The Flyway-run comparison above is the gate of INV-Q-10832 A: it is never switched off. */
    @Test
    fun `this test class switches no test off`() {
        val source = Path.of("src/test/kotlin/com/dynokore/gst/evidence/adapters/ConsumerSideMigrationTest.kt")
        Files.exists(source) shouldBe true
        val marker = "@" + "Disabled"
        Files.readString(source).contains(marker) shouldBe false
    }

    private fun assertEqual(
        fromGenerator: List<String>,
        fromMigrations: List<String>,
    ) {
        fromGenerator.isNotEmpty() shouldBe true
        fromMigrations.minus(fromGenerator.toSet()) shouldBe emptyList()
        fromGenerator.minus(fromMigrations.toSet()) shouldBe emptyList()
        fromMigrations shouldBe fromGenerator
    }

    /** The named files executed in order as the migration role, one script each, as the installer would. */
    private fun viaFiles(
        url: String,
        vararg names: String,
    ): List<String> {
        provision(url)
        DriverManager.getConnection(url, EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use { connection ->
            connection.createStatement().use { s -> names.forEach { s.execute(resource(it)) } }
            return snapshot(connection)
        }
    }

    @Test
    fun `the snapshot names the consumer-side objects, so a missing statement is a named difference`() {
        val withoutConsumerSide = scratch { url -> viaMigrations(url, "V1__evidence_schema.sql") }
        val full = scratch { url -> viaGenerator(url, generated) }
        val missing = full.minus(withoutConsumerSide.toSet())
        missing.any { it.startsWith("table evidence.inbox ") } shouldBe true
        missing.any { it.startsWith("table evidence.event_dead_letter ") } shouldBe true
        missing.any { it.startsWith("function evidence.event_dead_letter_record(") } shouldBe true
        missing.any { it.startsWith("function evidence.consumer_offset_advance(") } shouldBe true
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/db/migration/$name")) { "no migration $name" }.readText()

    private fun <T> scratch(block: (String) -> T): T {
        val name = "scratch_" + UUID.randomUUID().toString().replace("-", "")
        EvidenceDb.superuser().use { it.createStatement().use { s -> s.execute("create database $name") } }
        val url = EvidenceDb.jdbcUrl.substringBeforeLast('/') + "/$name"
        try {
            return block(url)
        } finally {
            EvidenceDb.superuser().use {
                it.createStatement().use { s -> s.execute("drop database if exists $name with (force)") }
            }
        }
    }

    private fun provision(url: String) {
        EvidenceDb.superuserOf(url).use {
            it.createStatement().use { s ->
                s.execute(checkNotNull(javaClass.getResource("/db/provision.sql")).readText())
            }
        }
    }

    /** The generator output, executed as the migration role in a schema created for it. */
    private fun viaGenerator(
        url: String,
        ddl: String,
    ): List<String> {
        provision(url)
        DriverManager.getConnection(url, EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use { connection ->
            connection.createStatement().use { s ->
                s.execute("grant usage on schema evidence to ${EvidenceDb.RUNTIME}")
                s.execute(ddl)
            }
            return snapshot(connection)
        }
    }

    /** The named migrations, in order, run by Flyway from a scratch directory (V2 is not among them). */
    private fun viaMigrations(
        url: String,
        vararg names: String,
    ): List<String> {
        provision(url)
        val directory: Path = Files.createTempDirectory("evidence-consumer-side")
        try {
            names.forEach { Files.writeString(directory.resolve(it), resource(it)) }
            Flyway
                .configure()
                .dataSource(url, EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD)
                .schemas(EvidenceDb.SCHEMA)
                .defaultSchema(EvidenceDb.SCHEMA)
                .locations("filesystem:$directory")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
        } finally {
            directory.toFile().deleteRecursively()
        }
        DriverManager.getConnection(url, EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use { return snapshot(it) }
    }

    private fun lines(
        connection: Connection,
        sql: String,
    ): List<String> =
        connection.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString(1))
                }
            }
        }

    /** One text line per object of schema `evidence`; the flyway history table is not part of the design. */
    private fun snapshot(connection: Connection): List<String> =
        queries(EvidenceDb.SCHEMA).flatMap { lines(connection, it) }.sorted()

    private fun queries(s: String): List<String> =
        listOf(
            tablesQuery(s),
            columnsQuery(s),
            policiesQuery(s),
            indexesQuery(s),
            constraintsQuery(s),
            viewsQuery(s),
            functionsQuery(s),
            triggersQuery(s),
            privilegesQuery(s),
        )

    private fun tablesQuery(s: String): String =
        """
        select 'table ' || n.nspname || '.' || c.relname || ' kind=' || c.relkind::text || ' owner=' ||
        pg_get_userbyid(c.relowner) || ' rls=' || c.relrowsecurity || ' force=' || c.relforcerowsecurity ||
        ' acl=' || coalesce((select string_agg(a::text, ',' order by a::text) from unnest(c.relacl) a), '')
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = '$s' and c.relkind in ('r', 'v', 'p') and c.relname <> 'flyway_schema_history'
        """.trimIndent()

    private fun columnsQuery(s: String): String =
        """
        select 'column ' || n.nspname || '.' || c.relname || '.' || a.attname || ' ' ||
        format_type(a.atttypid, a.atttypmod) || ' notnull=' || a.attnotnull || ' default=' ||
        coalesce(pg_get_expr(d.adbin, d.adrelid), '') || ' identity=' || a.attidentity::text
        from pg_attribute a join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace
        left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
        where n.nspname = '$s' and c.relkind in ('r', 'v', 'p') and a.attnum > 0 and not a.attisdropped
        and c.relname <> 'flyway_schema_history'
        """.trimIndent()

    private fun policiesQuery(s: String): String =
        """
        select 'policy ' || p.schemaname || '.' || p.tablename || ' ' || p.policyname || ' ' || p.permissive ||
        ' ' || p.cmd || ' roles=' || p.roles::text || ' using=' || coalesce(p.qual, '') ||
        ' check=' || coalesce(p.with_check, '')
        from pg_policies p where p.schemaname = '$s' and p.tablename <> 'flyway_schema_history'
        """.trimIndent()

    private fun indexesQuery(s: String): String =
        """
        select 'index ' || schemaname || '.' || tablename || ' ' || indexdef
        from pg_indexes where schemaname = '$s' and tablename <> 'flyway_schema_history'
        """.trimIndent()

    private fun constraintsQuery(s: String): String =
        """
        select 'constraint ' || n.nspname || '.' || c.relname || ' ' || k.conname || ' ' ||
        pg_get_constraintdef(k.oid)
        from pg_constraint k join pg_class c on c.oid = k.conrelid join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = '$s' and c.relname <> 'flyway_schema_history'
        """.trimIndent()

    private fun viewsQuery(s: String): String =
        """
        select 'view ' || n.nspname || '.' || c.relname || ' ' || pg_get_viewdef(c.oid)
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = '$s' and c.relkind = 'v'
        """.trimIndent()

    private fun functionsQuery(s: String): String =
        """
        select 'function ' || n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) ||
        ') owner=' || pg_get_userbyid(p.proowner) || ' acl=' ||
        coalesce((select string_agg(a::text, ',' order by a::text) from unnest(p.proacl) a), '') ||
        ' def=' || pg_get_functiondef(p.oid)
        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = '$s' and p.prokind in ('f', 'p')
        """.trimIndent()

    private fun triggersQuery(s: String): String =
        """
        select 'trigger ' || n.nspname || '.' || c.relname || ' ' || pg_get_triggerdef(t.oid)
        from pg_trigger t join pg_class c on c.oid = t.tgrelid join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = '$s' and not t.tgisinternal and c.relname <> 'flyway_schema_history'
        """.trimIndent()

    private fun privilegesQuery(s: String): String =
        """
        select 'privilege ' || table_schema || '.' || table_name || ' ' || grantee || ' ' || privilege_type
        from information_schema.role_table_grants
        where table_schema = '$s' and table_name <> 'flyway_schema_history'
        """.trimIndent()

    private companion object {
        const val CONSUMER_SIDE = "V3__evidence_consumer_side.sql"
    }
}
