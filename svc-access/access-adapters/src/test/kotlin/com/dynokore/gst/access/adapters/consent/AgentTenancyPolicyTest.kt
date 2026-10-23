package com.dynokore.gst.access.adapters.consent

import com.dynokore.gst.access.adapters.breakglass.ValueConstraintsChain
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.ElectionRequired
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.VariationPointKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** VP-74 (INV-REQ-2757): the form default, the live read and the switch-off check, with look-alike and list probes. */
class AgentTenancyPolicyTest {
    private val clock = Clock.fixed(java.time.Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC)
    private val installation = SettingTarget(null, PolicyScope.INSTALLATION_REF, null)
    private val day = LocalDate.of(2026, 10, 23)

    private fun reader(answer: () -> ResolvedSetting) =
        object : PolicyReader {
            override fun resolve(
                key: VariationPointKey,
                scope: PolicyScope,
                at: LocalDate,
            ): ResolvedSetting {
                assertEquals(VariationPointKey("VP-74"), key)
                assertEquals(ScopeLevel.INSTALLATION, scope.chain().single().level)
                return answer()
            }
        }

    private fun resolved(vararg options: String) =
        ResolvedSetting(
            VariationPointKey("VP-74"),
            day,
            options.map { SelectedOption(it) },
            PolicySource.CatalogueDefault,
        )

    private fun unset(): ResolvedSetting = throw ElectionRequired(VariationPointKey("VP-74"))

    @Test
    fun `with no value set agent tenancy is on in SaaS and off in a private installation`() {
        assertTrue(VariationPointAgentTenancy(reader(::unset), clock, saas = true).enabled())
        assertFalse(VariationPointAgentTenancy(reader(::unset), clock, saas = false).enabled())
    }

    @Test
    fun `a set value wins over the form default and only ON switches tenancy on`() {
        assertFalse(VariationPointAgentTenancy(reader { resolved("OFF") }, clock, saas = true).enabled())
        assertTrue(VariationPointAgentTenancy(reader { resolved("ON") }, clock, saas = false).enabled())
        listOf("on", "ON ", "OFF2", "", "ON,OFF").forEach {
            assertFalse(VariationPointAgentTenancy(reader { resolved(it) }, clock, saas = true).enabled(), it)
        }
        assertFalse(VariationPointAgentTenancy(reader { resolved("ON", "OFF") }, clock, saas = true).enabled())
    }

    @Test
    fun `any other failure to read is not answered with a default`() {
        assertThrows(IllegalStateException::class.java) {
            VariationPointAgentTenancy(reader { error("down") }, clock, saas = true).enabled()
        }
    }

    private fun check(
        live: Boolean,
        key: String,
        vararg options: String,
    ) = AgentTenancyConstraints { live }.check(
        installation,
        VariationPointKey(key),
        options.map { SelectedOption(it) },
        day,
    )

    @Test
    fun `switching off is refused while a consent is live and accepted when none is`() {
        assertTrue(check(live = true, "VP-74", "OFF") is ConstraintVerdict.Refuse)
        assertEquals(ConstraintVerdict.Accept, check(live = false, "VP-74", "OFF"))
        assertEquals(ConstraintVerdict.Accept, check(live = true, "VP-74", "ON"))
    }

    @Test
    fun `the check judges VP-74 only and reads the option by exact equality`() {
        assertNull(check(live = true, "VP-73", "OFF"))
        assertNull(check(live = true, "VP-740", "OFF"))
        assertEquals(ConstraintVerdict.Accept, check(live = true, "VP-74", "off"))
        assertEquals(ConstraintVerdict.Accept, check(live = true, "VP-74", "OFF2"))
        // a list is not a single option: it is not read as OFF
        assertEquals(ConstraintVerdict.Accept, check(live = true, "VP-74", "ON", "OFF"))
    }

    @Test
    fun `the combined check returns the first verdict and none for a point no check judges`() {
        val accept = ValueConstraintsStub(ConstraintVerdict.Accept)
        val refuse = ValueConstraintsStub(ConstraintVerdict.Refuse("no"))
        val none = ValueConstraintsStub(null)
        val key = VariationPointKey("VP-74")

        fun verdict(vararg checks: ValueConstraintsStub) =
            ValueConstraintsChain(checks.toList()).check(installation, key, emptyList(), day)
        assertNull(verdict(none, none))
        assertEquals(ConstraintVerdict.Accept, verdict(none, accept, refuse))
        assertTrue(verdict(refuse, accept) is ConstraintVerdict.Refuse)
        assertNull(verdict())
    }
}

private class ValueConstraintsStub(
    private val verdict: ConstraintVerdict?,
) : com.dynokore.platform.configresolver.ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ) = verdict
}
