package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.UUID

/**
 * INV-T-3983: `config_purge_subject` and `config_purge_tenant` (INV-Q-2938, owner ruling INV-Q-3953) delete only the
 * subject's or the bound tenant's rows, and refuse an unset actor, an unbound or platform tenant and another tenant
 * (INV-Q-3997); the runtime login has no DELETE. PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigPurgePostgresIT {
    private val harnesses = mutableMapOf<String, ConfigHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a purge removes only the subject's or the bound tenant's rows, and needs an actor and a tenant`(
        image: String,
    ) {
        val h = harnesses.getOrPut(image) { ConfigHarness(image) }
        val a = TenantId(UUID.randomUUID())
        val b = TenantId(UUID.randomUUID())
        listOf(a, b).forEach { seed(h, it) }

        fun resolved(tenant: TenantId) = h.rows("config_resolved_value", tenant)

        fun settings(tenant: TenantId) = h.rows("config_setting", tenant)
        val subject = ResolutionSubject("submission", "doc-1/v1")

        // The functions are called with the settings given, an absent one as the empty setting, so each refusal is the
        // function's own (a TenancyProbe transaction on the runtime login; the runner never binds an unset actor).
        val subjectPurge = "select svc.config_purge_subject('submission', 'doc-1/v1')"
        val tenantPurge = "select * from svc.config_purge_tenant('${a.value}')"
        listOf(subjectPurge, tenantPurge).forEach { sql ->
            assertEquals("42501", refusedRaw(h, sql, a.value.toString(), null), "actor unset: $sql")
            assertEquals("42501", refusedRaw(h, sql, null, "u"), "tenant unbound: $sql")
            assertEquals("42501", refusedRaw(h, sql, TenantId.PLATFORM.value.toString(), "u"), "platform: $sql")
        }
        assertEquals("42501", refusedRaw(h, tenantPurge, b.value.toString(), "u"), "another tenant")
        assertEquals(2L, resolved(a))
        val direct =
            h.refused {
                h.asTenant(a) {
                    h.app.inTransaction {
                        h.dsl.deleteFrom(DSL.table(DSL.name("svc", "config_resolved_value"))).execute()
                    }
                }
            }
        assertEquals("42501", direct)

        assertEquals(1, h.asTenant(a) { h.app.inTransaction { h.purge.purgeSubject(subject) } })
        assertEquals(1L, resolved(a))
        assertEquals(2L, resolved(b), "the same subject of another tenant stays")
        assertEquals(
            PurgedTenant(resolvedValues = 1, settings = 1),
            h.asTenant(a) { h.app.inTransaction { h.purge.purgeTenant(a) } },
        )
        assertEquals(0L, resolved(a) + settings(a))
        assertEquals(2L, resolved(b))
        assertEquals(1L, settings(b))
    }

    /** Runs [sql] in one probe transaction with `app.tenant_id` and `app.actor` as given; its refusal's SQLSTATE. */
    private fun refusedRaw(
        h: ConfigHarness,
        sql: String,
        tenant: String?,
        actor: String?,
    ): String? =
        h.refused {
            h.probe.unbound {
                fetchValue(
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true)",
                    tenant.orEmpty(),
                    actor.orEmpty(),
                )
                fetchRows(sql)
            }
        }

    /** Two resolved values (doc-1/v1, doc-2/v1) and one setting of [tenant], written as the runtime login. */
    private fun seed(
        h: ConfigHarness,
        tenant: TenantId,
    ) {
        val vp = VariationPointKey("VP-11")
        listOf("doc-1/v1", "doc-2/v1").forEach { ref ->
            h.asTenant(tenant) {
                h.app.inTransaction {
                    h.recorder.record(
                        ResolutionSubject("submission", ref),
                        ResolvedSetting(
                            vp,
                            LocalDate.now(),
                            listOf(SelectedOption("A")),
                            PolicySource.CatalogueDefault,
                        ),
                        PolicyScope.tenant(tenant),
                    )
                }
            }
        }
        val tenantScope = ScopeRef(ScopeLevel.TENANT, "${tenant.value}")
        h.setTenant(tenant, vp, tenantScope, listOf(SelectedOption("A")), LocalDate.now())
    }
}
