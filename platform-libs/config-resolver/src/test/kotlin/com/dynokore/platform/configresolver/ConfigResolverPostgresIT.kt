package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.TestCatalogue.ENTITY_POLICY
import com.dynokore.platform.configresolver.TestCatalogue.INSTALLATION_ONLY
import com.dynokore.platform.configresolver.TestCatalogue.MULTI
import com.dynokore.platform.configresolver.TestCatalogue.TYPED
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/** Resolution, recording, RLS and purge against real PostgreSQL 17 and 18 (STK-14, STK-15). */
@Tag("integration")
class ConfigResolverPostgresIT {
    private val a = TenantId(UUID.randomUUID())
    private val b = TenantId(UUID.randomUUID())
    private val submission = ResolutionSubject("submission", "doc-1/v1")
    private val march = LocalDate.of(2026, 3, 15)

    private companion object {
        val PROFILE = "small-business" to "3"
    }

    private fun one(key: String) = listOf(SelectedOption(key))

    private fun ConfigHarness.entityOverride(
        tenant: TenantId,
        option: String,
        from: LocalDate,
        key: VariationPointKey = ENTITY_POLICY,
    ) = setTenant(tenant, key, ScopeRef(ScopeLevel.ENTITY, "E1"), one(option), from, entity = "E1")

    private fun ConfigHarness.tenantProfile(
        tenant: TenantId,
        option: String,
        from: LocalDate,
    ) = setTenant(tenant, ENTITY_POLICY, ScopeRef(ScopeLevel.TENANT, "$tenant"), one(option), from, profile = PROFILE)

    private fun <T> ConfigHarness.inTx(
        tenant: TenantId,
        block: () -> T,
    ): T = asTenant(tenant) { app.inTransaction(block) }

    private fun ConfigHarness.resolveAndRecord(
        tenant: TenantId,
        scope: PolicyScope,
        key: VariationPointKey,
        at: LocalDate,
        subject: ResolutionSubject = submission,
    ): RecordedResolution = asTenant(tenant) {
        app.inTransaction { recorder.record(subject, reader.resolve(key, scope, at), scope) }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `RLS is enabled and forced on the tenant tables`(image: String) {
        ConfigHarness(image).use { h ->
            listOf("config_setting", "config_resolved_value").forEach { table ->
                assertEquals(
                    1L,
                    h.count(
                        "select count(*) from pg_class where oid = 'svc.$table'::regclass " +
                            "and relrowsecurity and relforcerowsecurity",
                    ),
                    table,
                )
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-2091 the entity value is applied and recorded with the entity as its source`(image: String) {
        ConfigHarness(image).use { h ->
            h.tenantProfile(a, "FOUR_EYES_PLUS", march.minusMonths(6))
            val entity = h.entityOverride(a, "SOLE_OPERATOR", march.minusMonths(1))

            val recorded = h.resolveAndRecord(a, PolicyScope.entity(a, "E1"), ENTITY_POLICY, march)

            assertEquals(one("SOLE_OPERATOR"), recorded.selected)
            assertEquals(PolicySource.Override(ScopeLevel.ENTITY, "E1", entity), recorded.source)
            assertEquals(listOf(recorded), h.inTx(a) { h.recorder.recorded(submission) })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-2092 the profile value is applied and recorded with the profile and version`(image: String) {
        ConfigHarness(image).use { h ->
            val profile = h.tenantProfile(a, "FOUR_EYES_PLUS", march.minusMonths(6))

            val recorded = h.resolveAndRecord(a, PolicyScope.entity(a, "E1"), ENTITY_POLICY, march)

            assertEquals(PolicySource.Profile("small-business", "3", ScopeLevel.TENANT, "$a", profile), recorded.source)
            val withProfile =
                "select count(*) from svc.config_resolved_value " +
                    "where source = 'PROFILE' and profile_id = 'small-business' and profile_version = '3'"
            assertEquals(1L, h.count(withProfile))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-2093 a value changed after the transaction date does not change the document`(image: String) {
        ConfigHarness(image).use { h ->
            val scope = PolicyScope.entity(a, "E1")
            val first = h.entityOverride(a, "SOLE_OPERATOR", LocalDate.of(2026, 1, 1))
            // The change is recorded after the document's transaction date and takes effect later still.
            h.entityOverride(a, "FOUR_EYES_PLUS", LocalDate.of(2026, 7, 1))

            val recorded = h.resolveAndRecord(a, scope, ENTITY_POLICY, march)
            assertEquals(one("SOLE_OPERATOR"), recorded.selected)
            assertEquals(PolicySource.Override(ScopeLevel.ENTITY, "E1", first), recorded.source)
            assertEquals(march, recorded.transactionDate)

            // A retry of the same document version keeps the first record (first write wins, INV-Q-2938).
            val retried = h.resolveAndRecord(a, scope, ENTITY_POLICY, LocalDate.of(2026, 8, 1))
            assertEquals(recorded, retried)
            assertEquals(1L, h.count("select count(*) from svc.config_resolved_value"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `an installation value is read by every tenant, and a by-form default applies`(image: String) {
        ConfigHarness(image, DeploymentForm.PRIVATE).use { h ->
            val typed = listOf(SelectedOption("OPERATOR_MAXIMUM", """{"hours":720}"""))
            val id = h.setInstallation(INSTALLATION_ONLY, typed, march.minusDays(1))

            listOf(a, b).forEach { tenant ->
                val recorded = h.resolveAndRecord(tenant, PolicyScope.entity(tenant, "E1"), INSTALLATION_ONLY, march)
                assertEquals(PolicySource.Override(ScopeLevel.INSTALLATION, "installation", id), recorded.source)
                assertEquals(typed, recorded.selected)
            }
            val byForm = h.inTx(a) { h.reader.resolve(TestCatalogue.BY_FORM, PolicyScope.tenant(a), march) }
            assertEquals(one("OFF"), byForm.selected)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `typed and multi-option values round-trip through settings and records`(image: String) {
        ConfigHarness(image).use { h ->
            val multi = listOf(SelectedOption("SMS"), SelectedOption("WEBHOOK"))
            h.setTenant(a, MULTI, ScopeRef(ScopeLevel.TENANT, a.toString()), multi, march.minusDays(1))
            val typed = listOf(SelectedOption("OTHER_PERIOD", """{"days":45}"""))
            h.setTenant(a, TYPED, ScopeRef(ScopeLevel.ENTITY, "E1"), typed, march.minusDays(1), entity = "E1")

            assertEquals(multi, h.resolveAndRecord(a, PolicyScope.tenant(a), MULTI, march).selected)
            assertEquals(typed, h.resolveAndRecord(a, PolicyScope.entity(a, "E1"), TYPED, march).selected)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `another tenant's settings and records are invisible`(image: String) {
        ConfigHarness(image).use { h ->
            h.entityOverride(a, "SOLE_OPERATOR", march.minusDays(1))
            h.resolveAndRecord(a, PolicyScope.entity(a, "E1"), ENTITY_POLICY, march)

            // Tenant B uses the same entity id: A's row must not leak through RLS (nor through the explicit predicate).
            val forB = h.inTx(b) { h.reader.resolve(ENTITY_POLICY, PolicyScope.entity(b, "E1"), march) }
            assertEquals(PolicySource.CatalogueDefault, forB.source)
            assertEquals(emptyList<RecordedResolution>(), h.inTx(b) { h.recorder.recorded(submission) })
            val both =
                "select (select count(*) from svc.config_setting) + (select count(*) from svc.config_resolved_value)"
            val visibleToB =
                h.inTx(b) {
                    h.app.require().createStatement().use { st ->
                        st.executeQuery(both).use {
                            it.next()
                            it.getLong(1)
                        }
                    }
                }
            assertEquals(0L, visibleToB)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the application role can neither change nor delete a setting or a record`(image: String) {
        ConfigHarness(image).use { h ->
            h.entityOverride(a, "SOLE_OPERATOR", march.minusDays(1))
            h.resolveAndRecord(a, PolicyScope.entity(a, "E1"), ENTITY_POLICY, march)

            listOf(
                "update svc.config_setting set option_keys = array['TWO_PERSON']",
                "delete from svc.config_setting",
                "update svc.config_resolved_value set option_keys = array['TWO_PERSON']",
                "delete from svc.config_resolved_value",
                "insert into svc.config_installation_setting (id, vp_key, option_keys, effective_from, set_by, " +
                    "reason, recorded_at) values (gen_random_uuid(), 'VP-72', array['PLATFORM_DEFAULT'], " +
                    "current_date, 'x', 'y', now())",
            ).forEach { sql -> assertThrows(SQLException::class.java, { h.execAs(h.app, a, sql) }, sql) }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the subject type is constrained in the table too`(image: String) {
        ConfigHarness(image).use { h ->
            val error =
                assertThrows(SQLException::class.java) {
                    h.execAs(
                        h.app,
                        a,
                        "insert into svc.config_resolved_value (id, tenant_id, subject_type, subject_ref, vp_key, " +
                            "transaction_date, option_keys, source, resolved_at) values (gen_random_uuid(), '$a', " +
                            "'Sub mission', 'x', 'VP-11', current_date, array['TWO_PERSON'], 'DEFAULT', now())",
                    )
                }
            assertTrue(error.sqlState == "23514", error.sqlState) // check_violation
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-2938 records are purged with their subject, and everything with its tenant`(image: String) {
        ConfigHarness(image).use { h ->
            listOf(a, b).forEach { tenant ->
                val scope = PolicyScope.entity(tenant, "E1")
                h.entityOverride(tenant, "SOLE_OPERATOR", march.minusDays(1))
                h.resolveAndRecord(tenant, scope, ENTITY_POLICY, march)
                h.resolveAndRecord(tenant, scope, ENTITY_POLICY, march, ResolutionSubject("submission", "doc-2/v1"))
            }

            assertEquals(1, h.asTenant(a) { h.app.inTransaction { h.purge.purgeSubject(submission) } })
            assertEquals(3L, h.count("select count(*) from svc.config_resolved_value"))

            val purged = h.asTenant(a) { h.app.inTransaction { h.purge.purgeTenant(a) } }
            assertEquals(PurgedTenant(resolvedValues = 1, settings = 1), purged)
            assertEquals(0L, h.count("select count(*) from svc.config_resolved_value where tenant_id = '$a'"))
            assertEquals(0L, h.count("select count(*) from svc.config_setting where tenant_id = '$a'"))
            assertEquals(2L, h.count("select count(*) from svc.config_resolved_value where tenant_id = '$b'"))
            assertEquals(1L, h.count("select count(*) from svc.config_setting where tenant_id = '$b'"))

            // Bound to A, a purge of B is refused (INV-T-3983): a tenant is purged only in its own transaction.
            val other =
                assertThrows(SQLException::class.java) {
                    h.asTenant(a) { h.app.inTransaction { h.purge.purgeTenant(b) } }
                }
            assertEquals("42501", other.sqlState)
            assertEquals(2L, h.count("select count(*) from svc.config_resolved_value where tenant_id = '$b'"))
        }
    }
}
