package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Outbox UPDATE hardening (owner ruling INV-Q-4334 B) after `write_installation_setting`, in outbox mode: the same
 * runtime login cannot rewrite the audit entry's or the PolicyChanged row's actor or payload, and cannot give either a
 * past `published_at` (a first mark is stored as database time, any later one is refused), so neither row can be
 * forged or aged into the purge's range. PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstallationOutboxTamperPostgresIT {
    private val harnesses = mutableMapOf<String, WriteHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the installation's outbox rows cannot be rewritten or backdated by the runtime login`(image: String) {
        val h = harnesses.getOrPut(image) { WriteHarness(image) }
        val args = h.staged()
        h.callInstallation(args)
        val audit = args.audit.get("id").asString()
        val changed = checkNotNull(args.policyChanged).get("id").asString()
        val rows = listOf(audit, changed)
        val before =
            h.count(
                "select count(*) from svc.outbox where id in ('$audit', '$changed') and jsonb_exists(data, 'scope')",
            )
        assertEquals(2L, before)

        rows.forEach { id ->
            listOf(
                "update svc.outbox set data = jsonb_set(data, '{actor}', '\"someone-else\"') " +
                    "where id = '$id' returning id",
                "update svc.outbox set data = jsonb_set(data, '{scope}', '\"TENANT\"') where id = '$id' returning id",
                "update svc.outbox set data = '{}' where id = '$id' returning id",
                "delete from svc.outbox where id = '$id' returning id",
            ).forEach { sql -> assertEquals("42501", refused(h, sql), sql) }
        }
        assertEquals(1L, h.count("select count(*) from svc.outbox where id = '$audit' and data ->> 'actor' = 'op'"))

        rows.forEach { id ->
            val past = "update svc.outbox set published_at = now() - interval '400 days' where id = '$id' returning id"
            // A first mark is stored as database time (the passed past time is ignored); a second is refused.
            h.raw(PLATFORM, "op") { fetchValue(past) }
            assertEquals("42501", refused(h, past), past)
        }
        val aged =
            "select count(*) from svc.outbox where id in ('$audit', '$changed') " +
                "and (published_at is null or published_at < now() - interval '1 minute')"
        assertEquals(0L, h.count(aged), "an installation row was given a past published_at")
    }

    private fun refused(
        h: WriteHarness,
        sql: String,
    ): String? = h.refusal { h.raw(PLATFORM, "op") { fetchValue(sql) } }.sqlState

    private companion object {
        val PLATFORM = TenantId.PLATFORM.value.toString()
    }
}
