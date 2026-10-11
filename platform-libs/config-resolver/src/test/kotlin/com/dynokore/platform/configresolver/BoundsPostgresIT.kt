package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.WriteCatalogue.CROSS_SCOPE
import com.dynokore.platform.configresolver.WriteCatalogue.GRACE
import com.dynokore.platform.configresolver.WriteCatalogue.GRACE_MAX
import com.dynokore.platform.configresolver.WriteCatalogue.INSTALLATION_BOUND
import com.dynokore.platform.configresolver.WriteCatalogue.RETRY_MAX
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
 * INV-REQ-1242 b5; rulings INV-Q-3202, INV-Q-3204, INV-Q-3206) against PostgreSQL 17 and 18. Installation
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
    ) = listOf(SelectedOption("OTHER", "{\"pair\":$pair,\"window\":$hours}"))

    private fun number(
        option: String,
        value: Int,
    ) = listOf(SelectedOption(option, "$value"))

    private fun Run.installation(cmd: SetPolicyCommand): PolicyChange {
        val writer = h.writer(platform = true, constraints = accept)
        return h.inPlatform { writer.setPolicy(cmd) }
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
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-REQ-780 VP-08 a tenant window may not exceed the installation value, capped when lowered`(image: String) {
        val r = Run(image)
        val w = r.h.writer(constraints = accept)
        val tenant = { selected: List<SelectedOption>, from: LocalDate ->
            set(CROSS_SCOPE, r.tenantScope, ScopeLevel.TENANT, selected, officer, from)
        }
        val atInstallation = { selected: List<SelectedOption>, from: LocalDate ->
            set(CROSS_SCOPE, r.tenantScope, ScopeLevel.INSTALLATION, selected, operator, from)
        }
        // The installation default PAIR_4H counts by its fixed 4 h window (INV-Q-3204).
        assertEquals(PolicyRefusal.LOOSER_THAN_BROADER, r.refusedPut(tenant(pair(6), today), w))
        assertEquals(0L, r.settings())
        r.put(tenant(pair(3, "[\"tenant.admin\",\"tax.approver\"]"), today), w) // the pair is never compared
        r.installation(atInstallation(pair(8), today))
        r.put(tenant(pair(6), today), w)
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
        assertEquals(cap("OTHER", "window", 6, 2, CROSS_SCOPE), capped.caps)
        assertTrue(capped.selected.single().valueJson!!.contains("deployment.operator")) // the stored pair is kept
        // The recorded resolution keeps the capped value and its caps (INV-REQ-1068).
        val subject = ResolutionSubject("submission", "S1")
        val recorded = r.h.inTenant(r.t) { r.h.recorder().record(subject, capped, r.tenantScope) }
        assertEquals(capped.caps, recorded.caps)
        assertEquals(capped.selected, recorded.selected)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-REQ-1248 VP-67 a window beyond the installation maximum is refused, capped when lowered`(image: String) {
        val r = Run(image)
        val tenant = { hours: Int ->
            set(INSTALLATION_BOUND, r.tenantScope, ScopeLevel.TENANT, number("CUSTOM", hours), integrator)
        }
        // The maximum's default HOURS_168 counts by its fixed value.
        assertEquals(PolicyRefusal.BEYOND_INSTALLATION_BOUND, r.refusedPut(tenant(200)))
        assertEquals(0L, r.settings())
        r.put(tenant(100))
        val lowered = number("CUSTOM", 80)
        r.installation(set(RETRY_MAX, r.tenantScope, ScopeLevel.INSTALLATION, lowered, operator, nextMonth))
        assertEquals("100", r.resolve(INSTALLATION_BOUND, r.tenantScope, today).selected.single().valueJson)
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
        assertEquals("80", r.capped(INSTALLATION_BOUND, number("CUSTOM", 150), nextMonth).selected.single().valueJson)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3202 a bounding row whose value is not a number refuses, until the operator sets one`(image: String) {
        val r = Run(image)
        val shorter = { days: Int ->
            set(GRACE, r.tenantScope, ScopeLevel.TENANT, number("SHORTER", days), approver)
        }
        // DAYS_14, as published, carries no typed value: the bound cannot be evaluated (fail closed).
        assertEquals(PolicyRefusal.BOUND_UNAVAILABLE, r.refusedPut(shorter(10)))
        r.installation(set(GRACE_MAX, r.tenantScope, ScopeLevel.INSTALLATION, number("CUSTOM", 20), operator))
        assertEquals(PolicyRefusal.BEYOND_INSTALLATION_BOUND, r.refusedPut(shorter(25)))
        r.put(shorter(10))
        assertEquals("10", r.resolve(GRACE, r.tenantScope).selected.single().valueJson)
        val unreadable = listOf(SelectedOption("SHORTER", "\"x\""))
        assertThrows(BoundUnavailable::class.java) { r.capped(GRACE, unreadable, today) }
    }
}
