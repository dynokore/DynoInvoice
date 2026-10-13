package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.WriteCatalogue.CROSS_ENTITY
import com.dynokore.platform.configresolver.WriteCatalogue.CROSS_SCOPE
import com.dynokore.platform.configresolver.WriteCatalogue.DEFAULT_PAIR
import com.dynokore.platform.configresolver.WriteCatalogue.GRACE
import com.dynokore.platform.configresolver.WriteCatalogue.GRACE_MAX
import com.dynokore.platform.configresolver.WriteCatalogue.INSTALLATION_BOUND
import com.dynokore.platform.configresolver.WriteCatalogue.RETRY_MAX
import com.dynokore.platform.configresolver.WriteCatalogue.UNTYPED_BOUNDED
import com.dynokore.platform.configresolver.WriteCatalogue.UNTYPED_MAX
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.LocalDate

/**
 * VP-08's cross-scope rule and the VP-57/VP-67 installation bound (INV-T-3176; INV-REQ-780 b1, INV-REQ-1248,
 * INV-REQ-1242 b5; rulings INV-Q-3202, INV-Q-3204, INV-Q-3206, INV-Q-3320) against PostgreSQL 17 and 18. Installation
 * values are shared by every tenant of a database, so each bounded row is used by one test only.
 */
@Tag("integration")
class BoundsPostgresIT : WritePathBase() {
    private val officer = actor("so", "security.officer")
    private val operator = actor("op", "platform.engineer", "security.officer")
    private val integrator = actor("ie", "integration.engineer")
    private val accept = ValueConstraints { _, _, _, _ -> ConstraintVerdict.Accept }
    private val ops = "[\"deployment.operator\",\"tax.approver\"]"

    private fun pair(
        hours: Int,
        pair: String = ops,
    ) = listOf(SelectedOption("OTHER_NAMED_PAIR", "{\"pair\":$pair,\"window\":$hours}"))

    private fun number(
        option: String,
        value: Int,
    ) = listOf(SelectedOption(option, "$value"))

    private fun Run.installation(cmd: SetPolicyCommand): PolicyChange {
        val writer = h.writer(platform = true, constraints = accept)
        return h.putInstallation(writer, cmd)
    }

    private fun Run.capped(
        key: VariationPointKey,
        selected: List<SelectedOption>,
        on: LocalDate,
    ) = h.inTenant(t) { h.reader().capToBounds(key, tenantScope, ScopeLevel.TENANT, selected, on) }

    private fun window(resolved: ResolvedSetting): BigDecimal {
        val json = resolved.selected.single().valueJson!!
        return BigDecimal(json.substringAfter("\"window\":").trimEnd('}'))
    }

    private fun cap(
        option: String,
        field: String,
        stored: Int,
        capped: Int,
        by: VariationPointKey,
    ) = listOf(PolicyCap(option, field, BigDecimal(stored), BigDecimal(capped), by, ScopeLevel.INSTALLATION))

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-REQ-780 VP-08 a tenant window may not exceed the installation value, capped when lowered`(image: String) {
        val r = Run(image)
        val w = r.h.writer(constraints = accept)
        val tenant = { selected: List<SelectedOption>, from: LocalDate ->
            set(CROSS_SCOPE, r.tenantScope, ScopeLevel.TENANT, selected, officer, from)
        }
        val atInstallation = { selected: List<SelectedOption>, from: LocalDate ->
            set(CROSS_SCOPE, r.tenantScope, ScopeLevel.INSTALLATION, selected, operator, from)
        }
        // Nothing set at installation: the default DEFAULT_PAIR_4H resolves with its default 4 h window (INV-Q-3320).
        assertEquals(PolicyRefusal.LOOSER_THAN_BROADER, r.refusedPut(tenant(pair(6), today), w))
        assertEquals(0L, r.settings())
        r.put(tenant(pair(3, "[\"tenant.admin\",\"tax.approver\"]"), today), w) // the pair is never compared
        r.installation(atInstallation(pair(8), today))
        r.put(tenant(pair(6), today), w)
        // A second tenant keeps the default pair, stored with a 4 h window.
        val r2 = Run(image)
        val pair4h = listOf(SelectedOption("DEFAULT_PAIR_4H", "{\"window\":4}"))
        r2.put(set(CROSS_SCOPE, r2.tenantScope, ScopeLevel.TENANT, pair4h, officer), w)
        // Tightening the installation value is never refused (INV-Q-3206) ...
        r.installation(atInstallation(pair(2), nextMonth))
        // ... a new tenant value is checked against it on every later date ...
        assertEquals(PolicyRefusal.LOOSER_THAN_BROADER, r.refusedPut(tenant(pair(5), today), w))
        assertEquals(2L, r.settings())
        // ... and the stored 6 h resolves capped at 2 h from that date, the source showing the cap.
        assertEquals(BigDecimal(6), window(r.resolve(CROSS_SCOPE, r.tenantScope, today)))
        val capped = r.resolve(CROSS_SCOPE, r.tenantScope, nextMonth)
        assertEquals(BigDecimal(2), window(capped))
        assertTrue(capped.source is PolicySource.Override)
        assertEquals(cap("OTHER_NAMED_PAIR", "window", 6, 2, CROSS_SCOPE), capped.caps)
        assertTrue(
            capped.selected
                .single()
                .valueJson!!
                .contains("deployment.operator"),
        ) // the stored pair is kept
        // The recorded resolution keeps the capped value and its caps (INV-REQ-1068).
        val subject = ResolutionSubject("submission", "S1")
        val recorded = r.h.inTenant(r.t) { r.h.recorder().record(subject, capped, r.tenantScope) }
        assertEquals(capped.caps, recorded.caps)
        assertEquals(capped.selected, recorded.selected)
        // DEFAULT_PAIR_4H keeps its key and shows the capped window (INV-Q-3206), stored with or without its value.
        val cappedPair = cap("DEFAULT_PAIR_4H", "window", 4, 2, CROSS_SCOPE)
        val fromStore = r2.resolve(CROSS_SCOPE, r2.tenantScope, nextMonth)
        assertEquals("DEFAULT_PAIR_4H", fromStore.selected.single().key)
        assertEquals(BigDecimal(2), window(fromStore))
        assertEquals(cappedPair, fromStore.caps)
        val bare = r2.capped(CROSS_SCOPE, listOf(SelectedOption("DEFAULT_PAIR_4H")), nextMonth)
        assertEquals(SelectedOption("DEFAULT_PAIR_4H", "{\"window\":2}"), bare.selected.single())
        assertEquals(cappedPair, bare.caps)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3320 VP-08 the default pair's window is 1 to 8 hours, default 4, tighten-only`(image: String) {
        val r = Run(image)
        val w = r.h.writer(constraints = accept)
        val defaultPair = { hours: Int -> listOf(SelectedOption("DEFAULT_PAIR_4H", "{\"window\":$hours}")) }
        val tenant = { selected: List<SelectedOption> ->
            set(DEFAULT_PAIR, r.tenantScope, ScopeLevel.TENANT, selected, officer)
        }
        val atInstallation = { selected: List<SelectedOption>, from: LocalDate ->
            set(DEFAULT_PAIR, r.tenantScope, ScopeLevel.INSTALLATION, selected, operator, from)
        }
        // Nothing set: the catalogue default DEFAULT_PAIR_4H resolves with its default 4 h window (INV-Q-3320) ...
        val nothingSet = r.resolve(DEFAULT_PAIR, r.tenantScope)
        assertEquals(SelectedOption("DEFAULT_PAIR_4H", "{\"window\":4}"), nothingSet.selected.single())
        assertEquals(PolicySource.CatalogueDefault, nothingSet.source)
        // ... and counts as 4 h in the cross-scope check.
        assertEquals(PolicyRefusal.LOOSER_THAN_BROADER, r.refusedPut(tenant(defaultPair(5)), w))
        // A write without a value or without its window is refused (INV-Q-3167, INV-Q-3548) ...
        val noValue = listOf(SelectedOption("DEFAULT_PAIR_4H"))
        val noWindow = listOf(SelectedOption("DEFAULT_PAIR_4H", "{}"))
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, r.refusedPut(tenant(noValue), w))
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, r.refusedPut(tenant(noWindow), w))
        // ... as is a window outside 1 to 8 hours; nothing is stored (INV-REQ-1269, INV-REQ-896 b2).
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, r.refusedPut(tenant(defaultPair(9)), w))
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, r.refusedPut(tenant(defaultPair(0)), w))
        assertEquals(0L, r.settings())
        val installationRefusal =
            refusal { r.installation(atInstallation(defaultPair(9), today)) }
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, installationRefusal)
        // The default pair at 8 h at installation, then at 6 h for the tenant: no longer fixed at 4 h.
        r.installation(atInstallation(defaultPair(8), today))
        r.put(tenant(defaultPair(6)), w)
        assertEquals(BigDecimal(6), window(r.resolve(DEFAULT_PAIR, r.tenantScope)))
        // The installation default pair tightened to 2 h from next month: a tenant 6 h is refused, whichever
        // option it is (the pair is never compared, INV-Q-3204) ...
        r.installation(atInstallation(defaultPair(2), nextMonth))
        assertEquals(PolicyRefusal.LOOSER_THAN_BROADER, r.refusedPut(tenant(defaultPair(6)), w))
        assertEquals(PolicyRefusal.LOOSER_THAN_BROADER, r.refusedPut(tenant(pair(6)), w))
        assertEquals(1L, r.settings())
        // ... and the stored 6 h resolves capped at 2 h from that date, the stored value unchanged (INV-Q-3206).
        assertEquals(BigDecimal(6), window(r.resolve(DEFAULT_PAIR, r.tenantScope, today)))
        val capped = r.resolve(DEFAULT_PAIR, r.tenantScope, nextMonth)
        assertEquals(SelectedOption("DEFAULT_PAIR_4H", "{\"window\":2}"), capped.selected.single())
        assertEquals(cap("DEFAULT_PAIR_4H", "window", 6, 2, DEFAULT_PAIR), capped.caps)
        // The default pair at 2 h is within the installation value.
        r.put(tenant(defaultPair(2)), w)
        assertEquals(BigDecimal(2), window(r.resolve(DEFAULT_PAIR, r.tenantScope, nextMonth)))
        assertEquals(emptyList<PolicyCap>(), r.resolve(DEFAULT_PAIR, r.tenantScope, nextMonth).caps)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-REQ-780 a later operating-profile version of the broader scope also bounds a write`(image: String) {
        val r = Run(image)
        val profile = { hours: Int, version: String, from: LocalDate ->
            val selected = number("CUSTOM", hours)
            val p1 = "P1" to version
            val tenant = ScopeLevel.TENANT
            set(CROSS_ENTITY, r.e1, tenant, selected, from = from, source = SettingSource.PROFILE, profile = p1)
        }
        val entity = { hours: Int -> set(CROSS_ENTITY, r.e1, ScopeLevel.ENTITY, number("CUSTOM", hours)) }
        r.put(profile(8, "v1", today))
        r.put(entity(6))
        r.put(profile(2, "v2", nextMonth))
        assertEquals(PolicyRefusal.LOOSER_THAN_BROADER, r.refusedPut(entity(5)))
        r.put(entity(2))
        assertEquals(
            "2",
            r
                .resolve(CROSS_ENTITY, r.e1, nextMonth)
                .selected
                .single()
                .valueJson,
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-REQ-1248 VP-67 a window beyond the installation maximum is refused, capped when lowered`(image: String) {
        val r = Run(image)
        val tenant = { hours: Int ->
            set(INSTALLATION_BOUND, r.tenantScope, ScopeLevel.TENANT, number("CUSTOM", hours), integrator)
        }
        // The maximum's default HOURS_168 is a typed value fixed at 168 hours (INV-Q-3223): 169 h is refused.
        assertEquals(PolicyRefusal.BEYOND_INSTALLATION_BOUND, r.refusedPut(tenant(169)))
        r.put(tenant(168))
        assertEquals(PolicyRefusal.BEYOND_INSTALLATION_BOUND, r.refusedPut(tenant(200)))
        assertEquals(1L, r.settings())
        r.put(tenant(100))
        val lowered = number("CUSTOM", 80)
        r.installation(set(RETRY_MAX, r.tenantScope, ScopeLevel.INSTALLATION, lowered, operator, nextMonth))
        assertEquals(
            "100",
            r
                .resolve(INSTALLATION_BOUND, r.tenantScope, today)
                .selected
                .single()
                .valueJson,
        )
        val capped = r.resolve(INSTALLATION_BOUND, r.tenantScope, nextMonth)
        assertEquals("80", capped.selected.single().valueJson)
        assertEquals(cap("CUSTOM", "", 100, 80, RETRY_MAX), capped.caps)
        // A per-object value (one webhook subscription) is validated and capped the same way.
        val writer = r.h.writer()
        val refused =
            refusal {
                r.h.inTenant(r.t) {
                    writer.validate(INSTALLATION_BOUND, r.tenantScope, ScopeLevel.TENANT, number("CUSTOM", 150), today)
                }
            }
        assertEquals(PolicyRefusal.BEYOND_INSTALLATION_BOUND, refused)
        assertEquals(
            "80",
            r
                .capped(INSTALLATION_BOUND, number("CUSTOM", 150), nextMonth)
                .selected
                .single()
                .valueJson,
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3223 VP-57 the default maximum of 14 days bounds a shorter window, capped when lowered`(image: String) {
        val r = Run(image)
        val shorter = { days: Int ->
            set(GRACE, r.tenantScope, ScopeLevel.TENANT, number("SHORTER", days), approver)
        }
        // DAYS_14, the default, is a typed value fixed at 14 days (INV-Q-3223).
        assertEquals(PolicyRefusal.BEYOND_INSTALLATION_BOUND, r.refusedPut(shorter(15)))
        assertEquals(0L, r.settings())
        r.put(shorter(14))
        r.installation(set(GRACE_MAX, r.tenantScope, ScopeLevel.INSTALLATION, number("CUSTOM", 7), operator, nextMonth))
        assertEquals(
            "14",
            r
                .resolve(GRACE, r.tenantScope)
                .selected
                .single()
                .valueJson,
        )
        val capped = r.resolve(GRACE, r.tenantScope, nextMonth)
        assertEquals("7", capped.selected.single().valueJson)
        assertEquals(cap("SHORTER", "", 14, 7, GRACE_MAX), capped.caps)
        assertEquals(PolicyRefusal.BEYOND_INSTALLATION_BOUND, r.refusedPut(shorter(10)))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3202 a bounding row whose value is not a number refuses, until the operator sets one`(image: String) {
        val r = Run(image)
        val shorter = { days: Int ->
            set(UNTYPED_BOUNDED, r.tenantScope, ScopeLevel.TENANT, number("SHORTER", days), approver)
        }
        // The maximum's default carries no typed value: the bound cannot be evaluated (fail closed).
        assertEquals(PolicyRefusal.BOUND_UNAVAILABLE, r.refusedPut(shorter(10)))
        r.installation(set(UNTYPED_MAX, r.tenantScope, ScopeLevel.INSTALLATION, number("CUSTOM", 20), operator))
        assertEquals(PolicyRefusal.BEYOND_INSTALLATION_BOUND, r.refusedPut(shorter(25)))
        r.put(shorter(10))
        assertEquals(
            "10",
            r
                .resolve(UNTYPED_BOUNDED, r.tenantScope)
                .selected
                .single()
                .valueJson,
        )
        val unreadable = listOf(SelectedOption("SHORTER", "\"x\""))
        assertThrows(BoundUnavailable::class.java) { r.capped(UNTYPED_BOUNDED, unreadable, today) }
    }
}
