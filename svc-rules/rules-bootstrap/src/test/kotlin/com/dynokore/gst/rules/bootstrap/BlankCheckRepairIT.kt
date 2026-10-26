package com.dynokore.gst.rules.bootstrap

import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.rulepackkernel.PackRegistryMigration
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * The blank checks of the Flyway-built `rules` schema refuse a value made only of U+FEFF (INV-T-10867, INV-AC-10874;
 * INV-REQ-854). The database is the one PostgresRules builds from every migration through Flyway, as rules_migrate;
 * Flyway drops U+FEFF from V2 and V3, so without V202610270900 every probe here fails. Expected facts are derived
 * from the generator's own statement groups, never typed in.
 */
@SpringBootTest(classes = [RulesApplication::class])
class BlankCheckRepairIT {
    private val probe: TenancyProbe = TenancyProbe.connect(pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")

    private val outbox = OutboxSchema.ddl("rules", "rules_runtime", "rules_sys")
    private val kernel = PackRegistryMigration.ddl("rules", "pack_artefact", "rules_runtime", "rules_sys")

    /** An affected function: its generator text, its regprocedure and the generator statements that set its state. */
    private data class Fn(
        val name: String,
        val regproc: String,
        val create: String,
        val end: String,
        val tail: String? = null,
    )

    private val functions =
        listOf(
            Fn(
                "full_sync_delete",
                "rules.full_sync_delete(text, text)",
                "create function",
                "revoke create on schema rules from rules_sys;",
            ),
            Fn(
                "event_dead_letter_record",
                "rules.event_dead_letter_record(text, text, bigint, uuid, text, text)",
                "create function",
                "revoke create on schema rules from rules_sys;",
            ),
            Fn(
                "pack_artefact_registered_by_insert",
                "rules.pack_artefact_registered_by_insert()",
                "CREATE FUNCTION",
                "\$\$;",
                "REVOKE EXECUTE ON FUNCTION rules.pack_artefact_registered_by_insert() FROM PUBLIC;",
            ),
            Fn(
                "register_platform_pack",
                "rules.register_platform_pack(uuid, text, text, text, date, date, text, jsonb, jsonb, bytea, " +
                    "jsonb, text)",
                "CREATE FUNCTION",
                "REVOKE CREATE ON SCHEMA rules FROM rules_sys;",
            ),
        )

    /** The generator's statements for [fn]: the create statement through the end of its group, and its own tail. */
    private fun statements(fn: Fn): String {
        val text = if (fn.create == "create function") outbox else kernel
        val start = text.indexOf("${fn.create} rules.${fn.name}(")
        require(start >= 0) { "no function ${fn.name}" }
        val end = text.indexOf(fn.end, start)
        require(end >= 0) { "the group of ${fn.name} has no end" }
        return text.substring(start, end + fn.end.length) + "\n" + (fn.tail ?: "")
    }

    private data class Facts(
        val owner: String,
        val secdef: Boolean,
        val config: String,
        val acl: String,
        val source: String,
    )

    private fun facts(
        connection: Connection,
        fn: Fn,
    ): Facts =
        connection
            .prepareStatement(
                "select pg_get_userbyid(proowner), prosecdef, coalesce(array_to_string(proconfig, ','), ''), " +
                    "coalesce(proacl::text, ''), prosrc from pg_proc where oid = ?::regprocedure",
            ).use { statement ->
                statement.setString(1, fn.regproc)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no function ${fn.name}" }
                    Facts(
                        rows.getString(1),
                        rows.getBoolean(2),
                        rows.getString(3),
                        rows.getString(4),
                        rows.getString(5),
                    )
                }
            }

    private fun superuser(): Connection = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    private fun runtime(): Connection = DriverManager.getConnection(pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")

    private fun count(table: String): Long =
        superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from rules.$table").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun sqlState(failure: Throwable): String? =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun message(failure: Throwable): String =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `each affected function has the owner, mode, search path and ACL of its group and the escape in its source`() {
        superuser().use { connection ->
            functions.forEach { fn ->
                val text = statements(fn)
                val found = facts(connection, fn)
                val owner =
                    Regex("""(?i)alter function [^;]* owner to (\w+);""").find(text)?.groupValues?.get(1)
                        ?: "rules_migrate"
                assertEquals(owner, found.owner, fn.name)
                assertEquals("security definer" in text.lowercase(), found.secdef, fn.name)
                val path = Regex("""(?i)set search_path = (.*?) as \$\$""").find(text)!!.groupValues[1]
                assertEquals("search_path=$path", found.config, fn.name)
                val acl =
                    found.acl
                        .trim('{', '}')
                        .split(',')
                        .filter { it.isNotEmpty() }
                // REVOKE ... FROM PUBLIC leaves no `=X/owner` entry; GRANT EXECUTE ... TO rules_runtime adds one
                val revokes = "from public" in text.lowercase()
                assertEquals(revokes, acl.none { it.startsWith("=") } && acl.isNotEmpty(), "${fn.name} $acl")
                val grants = "to rules_runtime" in text.lowercase()
                assertEquals(grants, "rules_runtime=X/${found.owner}" in acl, "${fn.name} $acl")
                assertTrue("\\uFEFF" in found.source, fn.name)
                assertTrue(found.source.none { it.code == FEFF_CODE }, fn.name)
            }
        }
    }

    @Test
    fun `the CHECK constraint holds the escape and refuses a value of only U+FEFF with 23514`() {
        superuser().use { connection ->
            connection
                .prepareStatement(
                    "select pg_get_constraintdef(oid), convalidated from pg_constraint " +
                        "where conrelid = 'rules.pack_artefact'::regclass and conname = ?",
                ).use { statement ->
                    statement.setString(1, CONSTRAINT)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next(), "constraint $CONSTRAINT")
                        assertTrue("\\uFEFF" in rows.getString(1))
                        assertTrue(rows.getString(1).none { it.code == FEFF_CODE })
                        assertTrue(rows.getBoolean(2))
                    }
                }

            // triggers are off for the probe (they bind app.actor); the CHECK is what is under test
            fun insert(value: String): Result<Int> {
                connection.autoCommit = false
                try {
                    connection.createStatement().use { it.execute("set local session_replication_role = replica") }
                    return runCatching {
                        connection
                            .prepareStatement(
                                "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, " +
                                    "effective_from, min_engine_version, manifest, checksums, signature, contents, " +
                                    "trusted_anchor, registered_by) values (?::uuid, 'p', 'MAPPING', ?, ?::uuid, " +
                                    "'2026-01-01'::date, '1.0.0', '{}'::jsonb, '{}'::jsonb, '\\x01'::bytea, " +
                                    "'{}'::jsonb, " +
                                    "'anchor', ?)",
                            ).use { statement ->
                                statement.setString(1, UUID.randomUUID().toString())
                                statement.setString(2, "v-" + UUID.randomUUID())
                                statement.setString(3, UUID.randomUUID().toString())
                                statement.setString(4, value)
                                statement.executeUpdate()
                            }
                    }
                } finally {
                    connection.rollback()
                    connection.autoCommit = true
                }
            }
            val refused = insert("$FEFF").exceptionOrNull()
            assertEquals("23514", sqlState(refused!!))
            assertTrue(CONSTRAINT in message(refused), message(refused))
            assertEquals("23514", sqlState(insert("$FEFF$FEFF").exceptionOrNull()!!))
            assertTrue(insert("\u200B").isSuccess)
            assertTrue(insert("a").isSuccess)
        }
    }

    private fun refused(
        state: String,
        block: () -> Unit,
    ): Throwable {
        val failure = shouldThrow<Throwable> { block() }
        sqlState(failure) shouldBe state
        return failure
    }

    @Test
    fun `full_sync_delete refuses a consumer of only U+FEFF and writes nothing, and a passes`() {
        val before = count("consumer_sequence")
        runtime().use { connection ->
            refused("22023") {
                connection.prepareStatement("select rules.full_sync_delete(?, 's')").use { statement ->
                    statement.setString(1, "$FEFF")
                    statement.executeQuery()
                }
            }
            assertEquals(before, count("consumer_sequence"))
            connection.prepareStatement("select rules.full_sync_delete('a', 's')").use { statement ->
                statement.executeQuery().use { assertTrue(it.next()) }
            }
        }
    }

    private fun callDeadLetter(
        connection: Connection,
        consumer: String,
    ) = connection
        .prepareStatement("select rules.event_dead_letter_record(?, 't', 1, null, 'r', 'raw')")
        .use { statement ->
            statement.setString(1, consumer)
            statement.executeQuery().use { it.next() }
        }

    @Test
    fun `event_dead_letter_record refuses a consumer of only U+FEFF and writes nothing, and a passes`() {
        val before = count("event_dead_letter")
        runtime().use { connection ->
            refused("22023") { callDeadLetter(connection, "$FEFF$FEFF") }
            assertEquals(before, count("event_dead_letter"))
            assertTrue(callDeadLetter(connection, "a"))
        }
        assertEquals(before + 1, count("event_dead_letter"))
    }

    private fun register(
        actor: String,
        packId: String,
    ): Any? =
        probe.inTenant(TenantId.PLATFORM, actor) {
            fetchValue(
                "select rules.register_platform_pack(?::uuid, ?, 'MAPPING', ?, '2026-01-01'::date, null, '1.0.0', " +
                    "'{}'::jsonb, '{}'::jsonb, ?, '{}'::jsonb, 'anchor')",
                UUID.randomUUID().toString(),
                packId,
                "v-" + UUID.randomUUID(),
                byteArrayOf(1),
            )
        }

    @Test
    fun `register_platform_pack refuses a blank field and a blank actor, and letters pass`() {
        val before = count("pack_artefact")
        // a pack field of only U+FEFF: the field check raises 22023
        refused("22023") { register("a", "$FEFF") }
        // an actor of only U+FEFF: the actor check raises 42501
        refused("42501") { register("$FEFF", "p") }
        assertEquals(before, count("pack_artefact"))
        register("a", "p")
        assertEquals(before + 1, count("pack_artefact"))
    }

    @Test
    fun `the registered_by trigger refuses an actor of only U+FEFF with 42501`() {
        val before = count("pack_artefact")
        val tenant = TenantId(UUID.randomUUID())
        val failure =
            refused("42501") {
                probe.inTenant(tenant, "$FEFF") {
                    fetchValue(
                        "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, " +
                            "effective_from, min_engine_version, manifest, checksums, signature, contents, " +
                            "trusted_anchor, registered_by) values (?::uuid, 'p', 'MAPPING', 'v-trigger', ?::uuid, " +
                            "'2026-01-01'::date, '1.0.0', '{}'::jsonb, '{}'::jsonb, ?, '{}'::jsonb, 'anchor', 'x') " +
                            "returning id",
                        UUID.randomUUID().toString(),
                        tenant.toString(),
                        byteArrayOf(1),
                    )
                }
            }
        assertTrue("the bound actor is not set" in message(failure), message(failure))
        assertEquals(before, count("pack_artefact"))
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)

        private val pg get() = PostgresRules.pg

        private const val FEFF_CODE = 0xFEFF
        private val FEFF: Char = FEFF_CODE.toChar()
        private const val CONSTRAINT = "pack_artefact_registered_by_check"
    }
}
