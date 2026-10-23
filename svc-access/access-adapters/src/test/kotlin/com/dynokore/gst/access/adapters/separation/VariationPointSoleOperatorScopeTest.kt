package com.dynokore.gst.access.adapters.separation

import com.dynokore.gst.access.application.separation.SoleOperatorScope
import com.dynokore.gst.access.application.separation.SoleOperatorScopePolicy
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The reader of VP-81 (INV-T-8292, INV-REQ-8287): the value of the Asia/Singapore date of the service clock, mapped by
 * string equality; anything else throws. The stand-in reader answers as config-resolver does: the latest value whose
 * effective date is not after the date asked for, else the catalogue default.
 */
class VariationPointSoleOperatorScopeTest {
    private val tenant = TenantId(UUID.randomUUID())

    // 2026-10-21T17:30:00Z is 01:30 on 2026-10-22 in Singapore
    private val clock = Clock.fixed(Instant.parse("2026-10-21T17:30:00Z"), ZoneOffset.UTC)

    private class Stored(
        val effective: LocalDate,
        val selected: List<SelectedOption>,
    )

    private fun reader(
        stored: List<Stored>,
        asked: MutableList<Pair<VariationPointKey, LocalDate>> = mutableListOf(),
        failure: RuntimeException? = null,
    ): PolicyReader =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PolicyReader::class.java)) { _, method, args ->
            check(method.name.startsWith("resolve")) { "the reader of VP-81 only resolves" }
            val key = VariationPointKey(args[0] as String)
            val scope = args[1] as PolicyScope
            val at = args[2] as LocalDate
            assertThat(scope.toString()).isEqualTo(PolicyScope.tenant(tenant).toString())
            asked += key to at
            failure?.let { throw it }
            val effective = stored.filter { !it.effective.isAfter(at) }.maxByOrNull { it.effective }
            ResolvedSetting(
                key,
                at,
                effective?.selected ?: listOf(SelectedOption("ONE_HOLDER_WITHDRAW_BLOCKED")),
                PolicySource.CatalogueDefault,
            )
        } as PolicyReader

    private val tenants: TenantContext =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, method, _ ->
            check(method.name.startsWith("current")) { "the reader only asks for the tenant" }
            // the value class TenantId is returned unboxed as its UUID by the mangled method
            tenant.value
        } as TenantContext

    private fun policy(reader: PolicyReader): SoleOperatorScopePolicy =
        VariationPointSoleOperatorScope(reader, tenants, clock)

    private fun option(key: String) = listOf(SelectedOption(key))

    @Test
    fun `a value effective on the Singapore date is returned and one effective the day after is not`() {
        val today = LocalDate.parse("2026-10-22")
        val asked = mutableListOf<Pair<VariationPointKey, LocalDate>>()
        val effectiveToday = reader(listOf(Stored(today, option("ONE_HOLDER"))), asked)
        assertThat(policy(effectiveToday).current()).isEqualTo(SoleOperatorScope.ONE_HOLDER)
        assertThat(asked).containsExactly(VariationPointKey("VP-81") to today)
        val effectiveTomorrow = reader(listOf(Stored(today.plusDays(1), option("ONE_HOLDER"))))
        assertThat(policy(effectiveTomorrow).current()).isEqualTo(SoleOperatorScope.ONE_HOLDER_WITHDRAW_BLOCKED)
    }

    @Test
    fun `each of the three keys maps to its constant`() {
        SoleOperatorScope.entries.forEach { value ->
            val stored = listOf(Stored(LocalDate.parse("2026-10-01"), option(value.name)))
            assertThat(policy(reader(stored)).current()).isEqualTo(value)
        }
    }

    @Test
    fun `a key that is none of the three throws and is never mapped to another constant`() {
        listOf("ONE_HOLDERS", "one_holder", "ONE_HOLDER ", " ONE_HOLDER", "ANY_NUMBER\n", "", "ANY").forEach { key ->
            val stored = listOf(Stored(LocalDate.parse("2026-10-01"), option(key)))
            assertThatThrownBy { policy(reader(stored)).current() }
                .describedAs("key '%s'", key)
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `two selected options throw`() {
        val both = listOf(SelectedOption("ONE_HOLDER"), SelectedOption("ANY_NUMBER"))
        val stored = listOf(Stored(LocalDate.parse("2026-10-01"), both))
        assertThatThrownBy { policy(reader(stored)).current() }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `a failed read throws and no default is returned`() {
        val failing = reader(emptyList(), failure = IllegalStateException("store down"))
        assertThatThrownBy { policy(failing).current() }.hasMessage("store down")
    }
}
