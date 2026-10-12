package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID

/**
 * INV-T-3983: `config_purge_subject` and `config_purge_tenant` (INV-Q-2938, owner ruling INV-Q-3953) delete only the
 * subject's or the bound tenant's rows, and refuse an unset actor, an unbound or platform tenant and another tenant
 * (INV-Q-3997); the runtime login has no DELETE. PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigPurgePostgresIT {
    private val harnesses = mutableMapOf<String, WriteHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a purge removes only the subject's or the bound tenant's rows, and needs an actor and a tenant`(
        image: String,
    ) {
        val h = harnesses.getOrPut(image) { WriteHarness(image) }
        val a = TenantId(UUID.randomUUID())
        val b = TenantId(UUID.randomUUID())
        listOf(a, b).forEach { seed(h, it) }
        fun rows(
            table: String,
            tenant: TenantId,
        ) = h.count("select count(*) from svc.$table where tenant_id = '${tenant.value}'")
        fun resolved(tenant: TenantId) = rows("config_resolved_value", tenant)
        fun settings(tenant: TenantId) = rows("config_setting", tenant)
        val subject = ResolutionSubject("submission", "doc-1/v1")

        val subjectPurge = "select svc.config_purge_subject('submission', 'doc-1/v1')"
        val tenantPurge = "select * from svc.config_purge_tenant('${a.value}')"
        listOf(subjectPurge, tenantPurge).forEach { sql ->
            assertEquals("42501", h.refusedRaw(sql, a.value.toString(), null), "actor unset: $sql")
            assertEquals("42501", h.refusedRaw(sql, null, "u"), "tenant unbound: $sql")
            assertEquals("42501", h.refusedRaw(sql, TenantId.PLATFORM.value.toString(), "u"), "platform: $sql")
        }
        assertEquals("42501", h.refusedRaw(tenantPurge, b.value.toString(), "u"), "another tenant")
        assertEquals(2L, resolved(a))
        val direct =
            assertThrows(SQLException::class.java) {
                h.inTenant(a) { h.execute("delete from svc.config_resolved_value") }
            }
        assertEquals("42501", direct.sqlState)

        assertEquals(1, h.inTenant(a) { h.purge().purgeSubject(subject) })
        assertEquals(1L, resolved(a))
        assertEquals(2L, resolved(b), "the same subject of another tenant stays")
        assertEquals(PurgedTenant(resolvedValues = 1, settings = 1), h.inTenant(a) { h.purge().purgeTenant(a) })
        assertEquals(0L, resolved(a) + settings(a))
        assertEquals(2L, resolved(b))
        assertEquals(1L, settings(b))
    }

    /** Two resolved values (subjects doc-1/v1 and doc-2/v1) and one setting of [tenant], inserted as the superuser. */
    private fun seed(
        h: WriteHarness,
        tenant: TenantId,
    ) {
        listOf("doc-1/v1", "doc-2/v1").forEach { ref ->
            h.asAdmin(
                "insert into svc.config_resolved_value (id, tenant_id, subject_type, subject_ref, vp_key, " +
                    "transaction_date, option_keys, source, resolved_at) values (gen_random_uuid(), " +
                    "'${tenant.value}', 'submission', '$ref', 'VP-11', current_date, array['A'], 'DEFAULT', now())",
            )
        }
        h.asAdmin(
            "insert into svc.config_setting (id, tenant_id, vp_key, scope_level, scope_id, option_keys, source, " +
                "effective_from, set_by, reason, recorded_at) values (gen_random_uuid(), '${tenant.value}', " +
                "'VP-11', 'TENANT', '${tenant.value}', array['A'], 'OVERRIDE', current_date, 'u', 'r', now())",
        )
    }
}
