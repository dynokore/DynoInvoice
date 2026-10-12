package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * One runtime login per service (INV-T-3978; owner rulings INV-Q-3951, INV-Q-3953, INV-Q-3965; INV-ADR-3968;
 * INV-REQ-854, INV-REQ-1189 b2, INV-REQ-1205): on PostgreSQL 16 (the minimum), 17 and 18, the runtime login reads and
 * writes no other tenant's row directly, and reaches across tenants only through the `<svc>_sys`-owned claim views and
 * SECURITY DEFINER functions, which give the claim columns only and delete only expired rows.
 */
@Tag("integration")
class SharedPoolIsolationIT {
    private val tenantA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val tenantB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private fun fixture(image: String): OutboxPostgresFixture = OutboxPostgresFixture(image).also {
        it.createService(SCHEMA, QueueSchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS))
        listOf(tenantA, tenantB).forEach { tenant -> seed(it, tenant) }
    }

    private fun seed(
        fixture: OutboxPostgresFixture,
        tenant: UUID,
    ) {
        fixture.runAsAdmin(
            """
            insert into $SCHEMA.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref,
                instance_id, correlation_id, causation_id, data, published_at)
            values ('fresh-$tenant', 't', 'ty', 's', 'sub', now(), '$tenant', 'e', 'i', 'c', 'c', '{"secret": 1}', null),
                   ('old-$tenant', 't', 'ty', 's', 'sub', now(), '$tenant', 'e', 'i', 'c', 'c', '{}',
                    now() - interval '40 days');
            insert into $SCHEMA.inbox (consumer, event_id, tenant_id, received_at)
            values ('c', 'old-$tenant', '$tenant', now() - interval '40 days'), ('c', 'new-$tenant', '$tenant', now());
            insert into $SCHEMA.consumer_sequence (consumer, source, subject, tenant_id, last_value)
            values ('c', '/gst/a-svc', 'sub', '$tenant', 3), ('c', '/gst/b-svc', 'sub', '$tenant', 4);
            insert into $SCHEMA.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
            values ('c', 't', 1, '$tenant', 'r', '{"secret": 2}');
            insert into $SCHEMA.work_queue (tenant_id, partition_key, lane, payload)
            values ('$tenant', 'k', 'LIVE', '{"secret": 3}');
            """.trimIndent(),
        )
    }

    private fun <T> runtime(
        fixture: OutboxPostgresFixture,
        tenant: UUID? = null,
        block: (Connection) -> T,
    ): T = fixture.runtimeDataSource().connection.use { connection ->
        if (tenant != null) {
            connection.createStatement().use { it.execute("select set_config('app.tenant_id', '$tenant', false)") }
        }
        block(connection)
    }

    private fun Connection.longs(sql: String): List<Long> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getLong(1)) } }
    }

    private fun Connection.strings(sql: String): List<String> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun Connection.update(sql: String): Int = createStatement().use { it.executeUpdate(sql) }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a claim across tenants returns the claim columns only`(image: String) {
        fixture(image).use { fixture ->
            runtime(fixture) { c ->
                val view = c.strings("select tenant_id::text from $SCHEMA.outbox_claim_v where published_at is null")
                assertEquals(setOf(tenantA.toString(), tenantB.toString()), view.toSet())
                assertEquals(
                    listOf("seq", "tenant_id", "published_at"),
                    c.strings(
                        "select attname from pg_attribute where attrelid = '$SCHEMA.outbox_claim_v'::regclass " +
                            "and attnum > 0 order by attnum",
                    ),
                )
                assertThrows(SQLException::class.java) { c.strings("select data::text from $SCHEMA.outbox_claim_v") }
                val triage = "$SCHEMA.event_dead_letter_triage_v"
                assertThrows(SQLException::class.java) { c.strings("select raw from $triage") }
                assertEquals(2, c.longs("select count(*) from $SCHEMA.event_dead_letter_triage_v").single())

                val claims = List(2) { c.strings(claimDue()).single() }
                assertEquals(setOf(tenantA.toString(), tenantB.toString()), claims.toSet())
                assertTrue(c.strings(claimDue()).isEmpty(), "each item is claimed once")
                val noPayload = "select payload::text from $SCHEMA.claim_due('LIVE', null, now(), now())"
                assertThrows(SQLException::class.java) { c.strings(noPayload) }
            }
        }
    }

    private fun claimDue(): String {
        val reclaimBefore = "now() - interval '5 minutes'"
        return "select claimed_tenant::text from $SCHEMA.claim_due('LIVE', null, now(), $reclaimBefore)"
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a direct select or delete across tenants as the runtime login returns nothing or is refused`(image: String) {
        fixture(image).use { fixture ->
            runtime(fixture) { c ->
                listOf("outbox", "inbox", "consumer_sequence", "event_dead_letter", "work_queue").forEach { table ->
                    assertEquals(0, c.longs("select count(*) from $SCHEMA.$table").single(), "$table without a tenant")
                }
                assertEquals(0, c.update("update $SCHEMA.outbox set published_at = now()"))
                assertEquals(0, c.update("delete from $SCHEMA.work_queue"))
            }
            runtime(fixture, tenantA) { c ->
                assertEquals(listOf(2L), c.longs("select count(*) from $SCHEMA.outbox"), "its own rows only")
                assertEquals(0, c.longs("select count(*) from $SCHEMA.outbox where tenant_id = '$tenantB'").single())
                assertEquals(0, c.update("update $SCHEMA.outbox set published_at = now() where tenant_id = '$tenantB'"))
                assertEquals(0, c.update("delete from $SCHEMA.work_queue where tenant_id = '$tenantB'"))
                listOf("outbox", "inbox", "consumer_sequence", "event_dead_letter").forEach { table ->
                    assertThrows(SQLException::class.java, { c.update("delete from $SCHEMA.$table") }, table)
                }
                assertThrows(SQLException::class.java) {
                    c.update(
                        "insert into $SCHEMA.work_queue (tenant_id, partition_key, lane, payload) " +
                            "values ('$tenantB', 'k', 'LIVE', '{}')",
                    )
                }
            }
            assertEquals(1L, count(fixture, "select count(*) from $SCHEMA.work_queue where tenant_id = '$tenantB'"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `purge and full-sync functions delete only what they name, and only expired rows`(image: String) {
        fixture(image).use { fixture ->
            fixture.runAsAdmin(
                "update $SCHEMA.event_dead_letter set status = 'DISCARDED', resolved_at = now() - interval '40 days'",
            )
            fixture.runAsAdmin(
                "insert into $SCHEMA.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw) " +
                    "values ('c', 't', 2, null, 'open', 'x')",
            )
            runtime(fixture) { c ->
                val outbox = "select $SCHEMA.outbox_purge_expired(now() - interval '30 days', 100)"
                assertEquals(listOf(2L), c.longs(outbox))
                assertEquals(listOf(2L), c.longs("select $SCHEMA.inbox_purge_expired(now() - interval '31 days', 100)"))
                assertEquals(
                    listOf(2L),
                    c.longs("select $SCHEMA.event_dead_letter_purge_expired(now() - interval '30 days', 100)"),
                )
                assertEquals(listOf(2L), c.longs("select $SCHEMA.full_sync_delete('c', '/gst/a-svc')"))
                assertThrows(SQLException::class.java) { c.longs("select $SCHEMA.full_sync_delete('c', '')") }
                assertThrows(SQLException::class.java) { c.longs("select $SCHEMA.outbox_purge_expired(now(), 100)") }
                assertThrows(SQLException::class.java) {
                    c.longs("select $SCHEMA.outbox_purge_expired(now() - interval '30 days', 0)")
                }
            }
            assertEquals(2L, count(fixture, "select count(*) from $SCHEMA.outbox"), "the unpublished rows stay")
            assertEquals(2L, count(fixture, "select count(*) from $SCHEMA.inbox"), "the recent rows stay")
            assertEquals(1L, count(fixture, "select count(*) from $SCHEMA.event_dead_letter where status = 'OPEN'"))
            val sequence = "select count(*) from $SCHEMA.consumer_sequence where source = "
            assertEquals(2L, count(fixture, "$sequence'/gst/b-svc'"))
            assertEquals(0L, count(fixture, "$sequence'/gst/a-svc'"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the catalogue holds no permissive policy for the runtime login and every definer function is fixed`(
        image: String,
    ) {
        fixture(image).use { fixture ->
            assertPolicies(fixture)
            assertDefinerFunctions(fixture)
            assertViewsAndRoles(fixture)
        }
    }

    /** No USING (true) or WITH CHECK (true) policy reaches the runtime login (or PUBLIC); forced RLS everywhere. */
    private fun assertPolicies(fixture: OutboxPostgresFixture) {
        val policies = "select count(*) from pg_policies where schemaname = '$SCHEMA'"
        val permissive = "(qual = 'true' or with_check = 'true')"
        assertEquals(0L, count(fixture, "$policies and ('$APP' = any(roles) or 'public' = any(roles)) and $permissive"))
        assertTrue(count(fixture, "$policies and qual = 'true'") > 0)
        assertEquals(
            0L,
            count(fixture, "$policies and qual = 'true' and not ('$SYS' = any(roles) or '$WORKER' = any(roles))"),
            "a USING (true) policy targets the sys role (or, in this stage, the transitional full-sync worker)",
        )
        TENANT_TABLES.forEach {
            val forced = "select count(*) from pg_class where oid = '$SCHEMA.$it'::regclass " +
                "and relrowsecurity and relforcerowsecurity"
            assertEquals(1L, count(fixture, forced), it)
        }
    }

    /** Every SECURITY DEFINER function: owned by sys, fixed search_path, EXECUTE for the runtime login only. */
    private fun assertDefinerFunctions(fixture: OutboxPostgresFixture) {
        val rows = fixture.adminDataSource().connection.use { c ->
            c.strings(
                "select p.proname || '|' || pg_get_userbyid(p.proowner) || '|' || " +
                    "coalesce(array_to_string(p.proconfig, ';'), '') || '|' || " +
                    "coalesce(array_to_string(p.proacl, ';'), '') from pg_proc p " +
                    "where p.pronamespace = '$SCHEMA'::regnamespace and p.prosecdef order by p.proname",
            )
        }
        assertEquals(DEFINERS, rows.map { it.substringBefore('|') })
        rows.map { it.split('|') }.forEach { row ->
            assertEquals(SYS, row[1], row.toString())
            assertEquals("search_path=pg_catalog, $SCHEMA, pg_temp", row[2], row.toString())
            // The owner's own entry and the runtime login; never PUBLIC (an entry without a grantee name).
            assertEquals(setOf(APP, SYS), row[3].split(';').map { it.substringBefore('=') }.toSet(), row.toString())
        }
        val others = "select count(*) from pg_proc where pronamespace = '$SCHEMA'::regnamespace and not prosecdef"
        assertEquals(0L, count(fixture, others), "no other function")
    }

    /** Sys-owned, non-security_invoker views; a runtime login that owns nothing; a sys role without login or CREATE. */
    private fun assertViewsAndRoles(fixture: OutboxPostgresFixture) {
        val views = fixture.adminDataSource().connection.use { c ->
            c.strings(
                "select relname || '|' || pg_get_userbyid(relowner) from pg_class where relkind = 'v' " +
                    "and relnamespace = '$SCHEMA'::regnamespace and coalesce(reloptions, '{}') = '{}' order by relname",
            )
        }
        assertEquals(listOf("event_dead_letter_triage_v|$SYS", "outbox_claim_v|$SYS"), views)
        assertEquals(0L, count(fixture, "select count(*) from pg_class where relowner = '$APP'::regrole"))
        assertEquals(0L, count(fixture, "select count(*) from pg_auth_members where member = '$APP'::regrole"))
        val sysRole = "select count(*) from pg_roles where rolname = '$SYS' and not rolcanlogin and not rolbypassrls"
        assertEquals(1L, count(fixture, sysRole))
        val create = fixture.adminDataSource().connection.use { c ->
            c.strings("select has_schema_privilege('$SYS', '$SCHEMA', 'CREATE')::text").single()
        }
        assertFalse(create.toBoolean())
    }

    private fun count(
        fixture: OutboxPostgresFixture,
        sql: String,
    ): Long = fixture.adminDataSource().connection.use { c -> c.longs(sql).single() }

    private companion object {
        const val SCHEMA = "payload"
        const val APP = OutboxPostgresFixture.APP
        const val SYS = OutboxPostgresFixture.SYS
        const val WORKER = OutboxPostgresFixture.WORKER
        val TENANT_TABLES =
            listOf("outbox", "outbox_sequence", "inbox", "consumer_sequence", "event_dead_letter", "work_queue")
        val DEFINERS = listOf(
            "claim_due",
            "event_dead_letter_purge_expired",
            "event_dead_letter_record",
            "event_dead_letter_resolve",
            "full_sync_delete",
            "inbox_purge_expired",
            "outbox_purge_expired",
        )
    }
}
