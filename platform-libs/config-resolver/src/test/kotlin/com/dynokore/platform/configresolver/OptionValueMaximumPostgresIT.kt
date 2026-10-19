package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-T-6538 (INV-Q-6529 A, INV-Q-6527 A): `config_option_value_maximum` returns the largest numeric member of the
 * value stored for an option over every tenant and scope, as a number only, and only in a platform transaction. The
 * VP-43 scenarios each use a variation point key of their own (VP-430 ...) because the maximum spans every tenant of
 * the shared container; the first uses VP-43 itself. PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OptionValueMaximumPostgresIT {
    private val harnesses = mutableMapOf<String, ConfigHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val past: LocalDate = today.minusDays(10)

    private fun harness(image: String) = harnesses.getOrPut(image) { ConfigHarness(image) }

    private fun maximum(
        h: ConfigHarness,
        key: String,
        option: String = LONGER,
    ): BigDecimal? =
        h.tenants.bindPlatform("operator-1") {
            h.app.inTransaction { h.maximum.maximum(VariationPointKey(key), option) }
        }

    private fun record(vararg members: Int): String =
        members.withIndex().joinToString(",", "{", "}") { "\"m${it.index}\":${it.value}" }

    /** What [tenantRow] stores besides the tenant, key and value; `null` takes the default of the test. */
    private data class RowSpec(
        val from: LocalDate? = null,
        val option: String = LONGER,
        val profile: Pair<String, String>? = null,
        val level: ScopeLevel = ScopeLevel.TENANT,
        val scopeId: String? = null,
    )

    private fun tenantRow(
        h: ConfigHarness,
        tenant: TenantId,
        key: String,
        value: String?,
        spec: RowSpec = RowSpec(),
    ): UUID {
        val scopeId = spec.scopeId ?: tenant.value.toString()
        return h.setTenant(
            tenant,
            VariationPointKey(key),
            Slot(
                ScopeRef(spec.level, scopeId),
                entity = if (spec.level == ScopeLevel.TENANT) null else scopeId,
                profile = spec.profile,
            ),
            listOf(SelectedOption(spec.option, value)),
            spec.from ?: past,
        )
    }

    /** Tenants A (7, 5, 5) and B (5, 10, 5) of [key]; C has no row. */
    private fun seedAb(
        h: ConfigHarness,
        key: String,
    ): Triple<TenantId, TenantId, TenantId> {
        val tenants = Triple(TenantId(UUID.randomUUID()), TenantId(UUID.randomUUID()), TenantId(UUID.randomUUID()))
        tenantRow(h, tenants.first, key, record(7, 5, 5))
        tenantRow(h, tenants.second, key, record(5, 10, 5))
        return tenants
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the maximum spans every tenant and scope and counts only the values in force or still to come`(
        image: String,
    ) {
        val h = harness(image)
        assertNull(maximum(h, "VP-43"), "no stored value")
        seedAb(h, "VP-43")
        assertEquals(0, BigDecimal(10).compareTo(maximum(h, "VP-43")), "tenants A, B and C")

        val (_, b, _) = seedAb(h, "VP-430")
        tenantRow(h, b, "VP-430", record(5, 6, 5)) // later-recorded, same scope and date: B's 10 is superseded
        assertEquals(0, BigDecimal(10).compareTo(maximum(h, "VP-43")), "VP-43 unchanged")
        assertEquals(0, BigDecimal(7).compareTo(maximum(h, "VP-430")), "10 superseded by 6")

        val (_, _, c) = seedAb(h, "VP-431")
        tenantRow(h, c, "VP-431", record(12, 1, 1), RowSpec(level = ScopeLevel.ENTITY, scopeId = "entity-c"))
        assertEquals(0, BigDecimal(12).compareTo(maximum(h, "VP-431")), "an entity-scope row")

        val (a, _, _) = seedAb(h, "VP-432")
        tenantRow(h, a, "VP-432", record(15, 1, 1), RowSpec(from = today.plusDays(1)))
        assertEquals(0, BigDecimal(15).compareTo(maximum(h, "VP-432")), "effective tomorrow")

        val old = TenantId(UUID.randomUUID())
        tenantRow(h, old, "VP-433", record(30, 1, 1), RowSpec(from = past.minusDays(5)))
        tenantRow(h, old, "VP-433", record(2, 1, 1), RowSpec(from = past))
        assertEquals(0, BigDecimal(2).compareTo(maximum(h, "VP-433")), "a value replaced by a later date")
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `an override hides the profile value of its scope and other options and keys do not count`(image: String) {
        val h = harness(image)
        val t = TenantId(UUID.randomUUID())
        tenantRow(h, t, "VP-434", record(20, 1, 1), RowSpec(profile = "profile-1" to "1"))
        tenantRow(h, t, "VP-434", record(8, 1, 1))
        assertEquals(0, BigDecimal(8).compareTo(maximum(h, "VP-434")), "PROFILE 20 beneath OVERRIDE 8")
        val profileOnly = TenantId(UUID.randomUUID())
        tenantRow(h, profileOnly, "VP-435", record(20, 1, 1), RowSpec(profile = "profile-1" to "1"))
        assertEquals(0, BigDecimal(20).compareTo(maximum(h, "VP-435")), "a profile value without an override")

        assertNull(maximum(h, "VP-436"), "no stored value of the key")
        tenantRow(h, TenantId(UUID.randomUUID()), "VP-437", null, RowSpec(option = "YEARS_5"))
        assertNull(maximum(h, "VP-437"), "YEARS_5 holds no value")
        assertNull(maximum(h, "VP-437", "YEARS_5"), "an option without a value")
        tenantRow(h, TenantId(UUID.randomUUID()), "VP-437", "\"P7Y\"")
        assertNull(maximum(h, "VP-437"), "a non-numeric member is ignored")

        tenantRow(h, TenantId(UUID.randomUUID()), "VP-438", "9")
        assertEquals(0, BigDecimal(9).compareTo(maximum(h, "VP-438")), "a bare number")
        assertNull(maximum(h, "VP-438", "YEARS_5"), "another option of the key")
        assertEquals(0, BigDecimal(10).compareTo(maximum(h, "VP-43")), "VP-43 unchanged by another key")
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `only a platform transaction reads it, the key and option are checked, and a number is all it returns`(
        image: String,
    ) {
        val h = harness(image)
        val a = TenantId(UUID.randomUUID())
        tenantRow(h, a, "VP-439", record(3, 1, 1))
        val call = "select svc.config_option_value_maximum('VP-439', 'LONGER')"

        fun raw(
            tenant: String?,
            sql: String,
        ) = h.refused {
            h.probe.unbound {
                fetchValue(
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', 'probe', true), " +
                        "set_config('app.binding_sig', ?, true)",
                    tenant.orEmpty(),
                    SignedBindingFixture.sign(tenant, "probe"),
                )
                fetchValue(sql)
            }
        }
        val key = VariationPointKey("VP-439")
        assertEquals("42501", h.refused { h.asTenant(a) { h.app.inTransaction { h.maximum.maximum(key, LONGER) } } })
        assertEquals("42501", raw(null, call), "tenant unbound")
        assertEquals("42501", raw(a.value.toString(), call), "a tenant")
        val platform = TenantId.PLATFORM.value.toString()
        assertEquals("22023", raw(platform, "select svc.config_option_value_maximum('x', 'LONGER')"))
        assertEquals("22023", raw(platform, "select svc.config_option_value_maximum('VP-4', 'LONGER')"))
        assertEquals("22023", raw(platform, "select svc.config_option_value_maximum(null, 'LONGER')"))
        assertEquals("22023", raw(platform, "select svc.config_option_value_maximum('VP-439', ' ')"))
        assertEquals("22023", raw(platform, "select svc.config_option_value_maximum('VP-439', null)"))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the port returns BigDecimal and the function is a numeric static definer for the runtime login only`(
        image: String,
    ) {
        val h = harness(image)
        assertTrue(
            OptionValueMaximum::class.java.declaredMethods
                .single()
                .returnType == BigDecimal::class.java,
        )
        val meta =
            h.probe
                .inTenant(TenantId.PLATFORM) {
                    fetchRows(
                        """
                        select pg_get_function_result(p.oid) as result, p.prosecdef as definer, r.rolname as owner,
                            p.proconfig::text as config, p.prosrc as src,
                            has_function_privilege('public', p.oid, 'execute') as public_exec,
                            has_function_privilege('config_app', p.oid, 'execute') as app_exec
                        from pg_proc p join pg_roles r on r.oid = p.proowner
                        where p.proname = 'config_option_value_maximum'
                        """.trimIndent(),
                    )
                }.single()
        assertEquals("numeric", meta["result"])
        assertEquals(true, meta["definer"])
        assertEquals("config_sys", meta["owner"])
        assertEquals("{\"search_path=pg_catalog, svc, pg_temp\"}", meta["config"])
        assertEquals(false, meta["public_exec"])
        assertEquals(true, meta["app_exec"])
        val dynamic = Regex("""\bexecute\b""", RegexOption.IGNORE_CASE)
        assertFalse(dynamic.containsMatchIn(meta["src"] as String), "dynamic SQL")
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the tenant policy stays and the sys role reads nine columns only and cannot change a row`(image: String) {
        val h = harness(image)
        val a = TenantId(UUID.randomUUID())
        val b = TenantId(UUID.randomUUID())
        tenantRow(h, a, "VP-440", record(1, 1, 1))
        tenantRow(h, b, "VP-440", record(2, 1, 1))
        val others = "select count(*) from svc.config_setting where tenant_id = ?::uuid"
        assertEquals(0L, h.probe.inTenant(a) { fetchValue(others, b.value.toString()) }, "tenant A selecting tenant B")
        assertEquals(1L, h.probe.inTenant(a) { fetchValue(others, a.value.toString()) })

        fun asSys(sql: String) =
            h.refused {
                h.ownerProbe.unbound {
                    fetchValue("select set_config('role', 'config_sys', true)")
                    fetchRows(sql)
                }
            }

        val granted =
            "tenant_id, vp_key, scope_level, scope_id, source, option_keys, option_values, effective_from, seq"
        h.ownerProbe.unbound {
            fetchValue("select set_config('role', 'config_sys', true)")
            assertTrue((fetchValue("select count(*) from (select $granted from svc.config_setting) x") as Long) >= 2)
        }
        listOf("entity_id", "id", "profile_id", "profile_version", "set_by", "reason", "recorded_at", "relaxation_ref")
            .forEach { assertEquals("42501", asSys("select $it from svc.config_setting"), it) }
        assertEquals("42501", asSys("select * from svc.config_setting"))
        assertEquals("42501", asSys("update svc.config_setting set vp_key = vp_key"))
        assertEquals("42501", asSys("insert into svc.config_setting (vp_key) values ('VP-1')"))
        val before = h.rows("config_setting", a) + h.rows("config_setting", b)
        h.ownerProbe.unbound {
            fetchValue("select set_config('role', 'config_sys', true)")
            val deleted = "with d as (delete from svc.config_setting returning 1) select count(*) from d"
            assertEquals(0L, fetchValue(deleted))
        }
        assertEquals(before, h.rows("config_setting", a) + h.rows("config_setting", b), "the sys delete reached no row")
    }

    private companion object {
        const val LONGER = "LONGER"
    }
}
