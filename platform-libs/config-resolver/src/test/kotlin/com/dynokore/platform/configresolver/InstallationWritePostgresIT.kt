package com.dynokore.platform.configresolver

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.configresolver.ConfigSchema.EventMode
import com.dynokore.platform.configresolver.WriteHarness.Companion.CAUSATION
import com.dynokore.platform.configresolver.WriteHarness.Companion.CORRELATION
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-T-3983: installation values and purges go only through the sys-owned SECURITY DEFINER functions (INV-ADR-3968
 * item 6, owner rulings INV-Q-3931, INV-Q-3953; coordinator rulings INV-Q-4254 A, INV-Q-4256, INV-Q-4316,
 * INV-Q-3997), on PostgreSQL 16, 17 and 18 (INV-Q-3965), in outbox mode and no-outbox mode (INV-Q-3142).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstallationWritePostgresIT {
    private val harnesses = mutableMapOf<Pair<String, EventMode>, WriteHarness>()
    private val operator = Actor("op", setOf("deployment.operator"))
    private val effective = InstallationCalls.effective
    private val backdated = InstallationCalls.backdated

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    private fun harness(
        image: String,
        mode: EventMode,
    ) = harnesses.getOrPut(image to mode) { WriteHarness(image, mode) }

    private fun cases() = IMAGES.flatMap { image -> EventMode.entries.map { Arguments.of(image, it) } }

    @ParameterizedTest(name = "{displayName}[{0}, {1}]")
    @MethodSource("cases")
    fun `an installation value, its audit entry and its PolicyChanged are stored together at database time`(
        image: String,
        mode: EventMode,
    ) {
        val h = harness(image, mode)
        val before = h.events(TenantId.PLATFORM).size
        val selected = listOf(SelectedOption("SMALLER", "1048576"))
        val cmd = command(WriteCatalogue.PLATFORM_MAXIMUM, selected)
        val writer = h.writer(form = DeploymentForm.PRIVATE, platform = true, clock = backdated)
        val change = h.putInstallation(writer, cmd)

        val id = change.version.id
        val stored = h.instant("select recorded_at from svc.config_installation_setting where id = '$id'")
        val now = h.instant("select now()")
        assertEquals(change.version.recordedAt, stored, "the version carries the stored time")
        assertTrue(stored.isAfter(backdated.instant().plusSeconds(DAY)), "a staged time was stored: $stored")
        assertTrue(!stored.isAfter(now.plusSeconds(MINUTE)) && stored.isAfter(now.minusSeconds(HOUR)), "$stored")
        assertEquals("op", h.text("select set_by from svc.config_installation_setting where id = '$id'"))

        assertStoredEvents(h, mode, before, change, stored)
    }

    /** The audit entry and PolicyChanged an installation write stored, per event mode, at the stored time. */
    private fun assertStoredEvents(
        h: WriteHarness,
        mode: EventMode,
        before: Int,
        change: PolicyChange,
        stored: Instant,
    ) {
        val events = h.events(TenantId.PLATFORM).drop(before)
        assertTrue(events.all { it.entityRef == EntityRef.PLATFORM.value && it.tenant == TenantId.PLATFORM.value })
        when (mode) {
            EventMode.OUTBOX -> {
                assertEquals(listOf("audit", "event"), events.map { it.kind })
                // Both rows carry the database time of the value (INV-Q-4316), their fixed type and topic, no document.
                val rows =
                    "select count(*) from svc.outbox where subject = 'config:VP-78:INSTALLATION' " +
                        "and event_time = '$stored' and doc_uuid is null and transaction_date is null " +
                        "and docsequence is null"
                assertEquals(2L, h.count(rows))
                val audit = "$rows and id = '${change.auditEventId}' and type = '${WriteHarness.AUDIT}'"
                assertEquals(1L, h.count("$audit and topic = '${WriteHarness.AUDIT_TOPIC}'"))
                val type = JdbcPolicyWriter.POLICY_CHANGED
                val changed = "$rows and id = '${change.policyChangedEventId}' and type = '$type'"
                assertEquals(1L, h.count("$changed and topic = '${WriteHarness.POLICY_CHANGED_TOPIC}'"))
            }

            EventMode.NO_OUTBOX -> {
                assertEquals(listOf("audit"), events.map { it.kind })
                assertEquals(null, change.policyChangedEventId)
                val entry =
                    "select count(*) from svc.audit_entry where id = '${change.auditEventId}' " +
                        "and recorded_at = '$stored' and actor = 'op' and action = 'setPolicy' and scope = 'INSTALLATION'"
                assertEquals(1L, h.count(entry))
                assertEquals(0L, h.count("select count(*) from pg_class where relname = 'outbox'"))
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}, {1}]")
    @MethodSource("cases")
    fun `the runtime login cannot write an installation value directly, and every function is the sys role's`(
        image: String,
        mode: EventMode,
    ) {
        val h = harness(image, mode)
        val insert =
            "insert into svc.config_installation_setting (id, vp_key, option_keys, effective_from, set_by, reason, " +
                "recorded_at) values (gen_random_uuid(), 'VP-78', array['SMALLER'], current_date, 'op', 'x', now())"
        val refused = assertThrows(SQLException::class.java) { h.inPlatform("op") { h.execute(insert) } }
        assertEquals("42501", refused.sqlState)
        listOf("insert", "update", "delete", "truncate").forEach {
            val table = "svc.config_installation_setting"
            assertFalse(h.bool("select has_table_privilege('config_app', '$table', '$it')"), it)
        }
        listOf("update", "delete").forEach { privilege ->
            listOf("config_setting", "config_resolved_value", "config_relaxation_acknowledgement").forEach {
                val granted = h.bool("select has_table_privilege('config_app', 'svc.$it', '$privilege')")
                assertFalse(granted, "$privilege $it")
            }
        }
        val functions =
            "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                "where n.nspname = 'svc' and p.proname in ('write_installation_setting', 'config_purge_subject', " +
                "'config_purge_tenant')"
        assertEquals(3L, h.count(functions))
        assertEquals(
            3L,
            h.count(
                "$functions and p.prosecdef and pg_get_userbyid(p.proowner) = 'config_sys' " +
                    "and p.proconfig = array['search_path=pg_catalog, svc, pg_temp'] " +
                    "and has_function_privilege('config_app', p.oid, 'execute') " +
                    "and not exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0) " +
                    "and (select array_agg(pg_get_userbyid(a.grantee)::text order by pg_get_userbyid(a.grantee)) " +
                    "from aclexplode(p.proacl) a " +
                    "where a.privilege_type = 'EXECUTE') = array['config_app', 'config_sys']",
            ),
            "SECURITY DEFINER, owned by config_sys, fixed search_path, EXECUTE to the runtime login only",
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}, {1}]")
    @MethodSource("cases")
    fun `a failure after the value is inserted leaves nothing stored`(
        image: String,
        mode: EventMode,
    ) {
        val h = harness(image, mode)
        val args = h.staged()
        // The audit row's id is taken already, so its insert fails after the value's (all or none, INV-Q-3931).
        val taken = args.audit.get("id").asString()
        when (mode) {
            EventMode.OUTBOX -> {
                h.asAdmin(
                    "insert into svc.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref, " +
                        "instance_id, correlation_id, causation_id, data) values ('$taken', 't', 'x', '/gst/x', 's', " +
                        "now(), '${TenantId.PLATFORM.value}', 'x', 'i', 'c', 'c', '{}')",
                )
            }

            EventMode.NO_OUTBOX -> {
                h.asAdmin(
                    "insert into svc.audit_entry (id, tenant_id, entity_ref, recorded_at, source, subject, " +
                        "instance_id, correlation_id, causation_id, actor, action, scope) values ('$taken', " +
                        "'${TenantId.PLATFORM.value}', 'x', now(), '/gst/x', 's', 'i', 'c', 'c', 'a', 'a', 'a')",
                )
            }
        }
        val counts = h.stored()
        val failure = assertThrows(SQLException::class.java) { h.callInstallation(args) }
        assertEquals("23505", failure.sqlState) // unique_violation, raised after the value's insert
        assertEquals(counts, h.stored())
        assertEquals(1, h.callInstallation(h.staged()).size, "a valid call still writes")
    }

    private fun command(
        key: VariationPointKey,
        selected: List<SelectedOption>,
    ) = SetPolicyCommand(
        key,
        PolicyScope.installation(),
        ScopeLevel.INSTALLATION,
        selected,
        effective,
        "platform maximum",
        operator,
        CORRELATION,
        CAUSATION,
        SettingSource.OVERRIDE,
        null,
        null,
        null,
    )

    companion object {
        private val IMAGES = listOf("postgres:16", "postgres:17", "postgres:18")
        private const val MINUTE = 60L
        private const val HOUR = 3600L
        private const val DAY = 86_400L
    }
}
