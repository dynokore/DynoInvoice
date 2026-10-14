package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.APP
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.SYS
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID

/**
 * INV-T-4616 (coordinator ruling INV-Q-4614 (A)): the full-sync delete, the dead-letter record and the generated
 * per-column clear function refuse a whitespace-only argument (ISO controls and the caseId whitespace set) by
 * tenancy-kernel's shared definition, with their own error and nothing written or deleted, even where rows hold that
 * very value. A value with a visible character, surrounded by whitespace, is matched and stored unchanged.
 * PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlankValuesIT {
    private val tenant = UUID.randomUUID()
    private val fixtures = mutableMapOf<String, OutboxPostgresFixture>()

    @AfterAll
    fun close() = fixtures.values.forEach { it.close() }

    private fun fixture(image: String) =
        fixtures.getOrPut(image) {
            OutboxPostgresFixture(image).also { f ->
                f.createService(
                    SCHEMA,
                    """
                    create table $SCHEMA.rm_view (consumer text not null, tenant_id uuid not null, note text not null);
                    ${TenantRlsMigration.ddl(SCHEMA, "rm_view", policyName = "rm_view_tenant_isolation")}
                    grant select, insert, update, delete on $SCHEMA.rm_view to $APP;
                    ${OutboxSchema.readModelClearFunction(SCHEMA, "rm_view", APP, SYS, "consumer")}
                    """.trimIndent(),
                )
                // Rows holding each blank value itself, which a blank argument that passed the check would delete.
                (BLANKS.map { it.second } + VISIBLE).forEachIndexed { i, value ->
                    f.admin(
                        "insert into $SCHEMA.consumer_sequence (consumer, source, subject, tenant_id, last_value) " +
                            "values (?, ?, 'sub-$i', ?, 1), (?, '/gst/a-svc', 'sub-$i', ?, 1), " +
                            "('c', ?, 'sub-$i', ?, 1)",
                        value,
                        value,
                        tenant,
                        value,
                        tenant,
                        value,
                        tenant,
                    )
                    f.admin("insert into $SCHEMA.rm_view (consumer, tenant_id, note) values (?, ?, 'n')", value, tenant)
                }
            }
        }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `each function refuses a whitespace-only argument and writes or deletes nothing`(image: String) {
        val f = fixture(image)
        val missed = mutableListOf<String>()
        BLANKS.forEach { (name, value) ->
            val calls =
                listOf(
                    Triple("full sync consumer", FULL_SYNC, listOf(value, "/gst/a-svc")),
                    Triple("full sync source", FULL_SYNC, listOf("c", value)),
                    Triple("dead letter consumer", DEAD_LETTER, listOf(value, tenant.toString())),
                    Triple("clear by column", CLEAR, listOf(value)),
                )
            calls.forEach { (what, call, arguments) ->
                val before = f.counts()
                val refused = runCatching { f.runtime(call.first, arguments) }.exceptionOrNull()
                if (refused !is SQLException || refused.sqlState != "22023" || call.second !in refused.message!!) {
                    missed += "$what $name: ${refused?.message ?: "accepted"}"
                }
                assertEquals(before, f.counts(), "$what $name wrote or deleted")
            }
        }
        assertEquals(emptyList<String>(), missed)

        VISIBLE.forEach { value ->
            assertEquals(1L, f.runtime(FULL_SYNC.first, listOf(value, "/gst/a-svc")), "full sync consumer '$value'")
            assertEquals(1L, f.runtime(FULL_SYNC.first, listOf("c", value)), "full sync source '$value'")
            assertEquals(1L, f.runtime(CLEAR.first, listOf(value)), "clear '$value'")
            val id = f.runtime(DEAD_LETTER.first, listOf(value, tenant.toString()))
            assertEquals(value, f.string("select consumer from $SCHEMA.event_dead_letter where id = $id"))
        }
    }

    private fun OutboxPostgresFixture.counts() =
        listOf("consumer_sequence", "event_dead_letter", "rm_view").map {
            string("select count(*) from $SCHEMA.$it")!!.toLong()
        }

    private fun OutboxPostgresFixture.admin(
        sql: String,
        vararg args: Any,
    ) = adminDataSource().connection.use { c ->
        c.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
            s.executeUpdate()
        }
    }

    private fun OutboxPostgresFixture.string(sql: String): String? =
        adminDataSource().connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
        }

    /** Calls [sql] as the runtime login with the tenant bound; returns the function's bigint result. */
    private fun OutboxPostgresFixture.runtime(
        sql: String,
        arguments: List<String>,
    ): Long =
        runtimeDataSource().connection.use { c ->
            c.autoCommit = false
            try {
                call(c, sql, arguments).also { c.commit() }
            } catch (failure: SQLException) {
                c.rollback()
                throw failure
            }
        }

    private fun call(
        c: java.sql.Connection,
        sql: String,
        arguments: List<String>,
    ): Long {
        c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }
        return c.prepareStatement(sql).use { s ->
            arguments.forEachIndexed { i, value -> s.setString(i + 1, value) }
            s.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private companion object {
        const val SCHEMA = "payload"

        /** Each function's call and the message of its refusal. */
        val FULL_SYNC =
            "select $SCHEMA.${OutboxSchema.FULL_SYNC_DELETE}(?, ?)" to "a full sync names one consumer and one source"
        val DEAD_LETTER =
            "select $SCHEMA.${OutboxSchema.DEAD_LETTER_RECORD}(?, 't', 1, ?::uuid, 'r', '{}')" to
                "a dead letter names its consumer, topic, offset, reason and record"
        val CLEAR = "select $SCHEMA.rm_view_full_sync_clear(?)" to "must not be blank"

        /** Whitespace-only values: ISO controls and the caseId whitespace set (INV-Q-4614), alone and mixed. */
        val BLANKS =
            listOf(
                "tab" to "\t",
                "newline" to "\n",
                "CR" to "\r",
                "U+0085" to "\u0085",
                "U+00A0" to "\u00A0",
                "U+1680" to "\u1680",
                "U+2000" to "\u2000",
                "U+200A" to "\u200A",
                "U+2028" to "\u2028",
                "U+2029" to "\u2029",
                "U+202F" to "\u202F",
                "U+205F" to "\u205F",
                "U+3000" to "\u3000",
                "U+FEFF" to "",
                "U+0001" to "\u0001",
                "U+009F" to "\u009F",
                "mix" to " \u00A0\t\u3000\r\n\uFEFF",
            )

        /** Values with a visible character, matched and stored unchanged. */
        val VISIBLE = listOf(" \t c-1 \n ", "x\u200By", "CASE 5521")
    }
}
