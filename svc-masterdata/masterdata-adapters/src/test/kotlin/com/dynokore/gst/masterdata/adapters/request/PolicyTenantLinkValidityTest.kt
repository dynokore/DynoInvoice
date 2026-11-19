package com.dynokore.gst.masterdata.adapters.request

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
 * INV-T-24785 (INV-REQ-24775, VP-83): the reader resolves VP-83 at tenant scope on the business date on every call,
 * gives null for DAYS_7 and the stored whole number for CUSTOM without clamping it, and fails closed on anything else.
 */
class PolicyTenantLinkValidityTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)

    private val tenants: TenantContext =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, method, _ ->
            check(method.name.startsWith("current")) { "unexpected call ${method.name}" }
            tenant.value
        } as TenantContext

    private class Seen(
        val key: VariationPointKey,
        val scope: PolicyScope,
        val on: LocalDate,
    )

    private fun reader(
        seen: MutableList<Seen>,
        selected: () -> List<SelectedOption>,
    ): PolicyReader =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PolicyReader::class.java)) { _, method, args ->
            check(method.name.startsWith("resolve")) { "unexpected call ${method.name}" }
            val vp = args[0].let { if (it is String) VariationPointKey(it) else it as VariationPointKey }
            seen += Seen(vp, args[1] as PolicyScope, args[2] as LocalDate)
            ResolvedSetting(vp, args[2] as LocalDate, selected(), PolicySource.CatalogueDefault)
        } as PolicyReader

    private fun days(
        vararg options: SelectedOption,
        seen: MutableList<Seen> = mutableListOf(),
    ): Long? = PolicyTenantLinkValidity(reader(seen) { options.toList() }, tenants, clock).days()

    private fun custom(valueJson: String?) = SelectedOption("CUSTOM", valueJson)

    @Test
    fun `INV-AC-24782 DAYS_7 gives null so the installation default applies`() {
        assertThat(days(SelectedOption("DAYS_7", null))).isNull()
    }

    @Test
    fun `INV-AC-24782 CUSTOM 1, 14 and 30 give the stored days`() {
        assertThat(days(custom("1"))).isEqualTo(1L)
        assertThat(days(custom("14"))).isEqualTo(14L)
        assertThat(days(custom("30"))).isEqualTo(30L)
    }

    @Test
    fun `INV-AC-24782 CUSTOM 0, 31 and -1 are returned as stored, the use case refuses them`() {
        assertThat(days(custom("0"))).isEqualTo(0L)
        assertThat(days(custom("31"))).isEqualTo(31L)
        assertThat(days(custom("-1"))).isEqualTo(-1L)
    }

    @Test
    fun `INV-AC-24782 CUSTOM without a whole number, an unknown key and a bad selection each throw`() {
        listOf(null, "", "\"x\"", "2.5", "null", "{}", "99999999999999999999").forEach {
            assertThatThrownBy { days(custom(it)) }.describedAs("value $it").isInstanceOf(RuntimeException::class.java)
        }
        listOf("DAYS_07", "days_7", "DAYS_7 ", "custom", "").forEach {
            assertThatThrownBy { days(SelectedOption(it, null)) }
                .describedAs("key '$it'")
                .isInstanceOf(IllegalStateException::class.java)
        }
        assertThatThrownBy { days(custom("14"), custom("15")) }.isInstanceOf(IllegalStateException::class.java)
        // ResolvedSetting itself refuses an empty selection, so the double fails before the reader sees it.
        assertThatThrownBy { days() }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `INV-AC-24782 VP-83 is resolved at tenant scope of the bound context on the business date`() {
        val seen = mutableListOf<Seen>()
        days(SelectedOption("DAYS_7", null), seen = seen)
        assertThat(seen.single().key).isEqualTo(VariationPointKey("VP-83"))
        assertThat(seen.single().scope.toString()).startsWith("TENANT:${tenant.value} >")
        assertThat(seen.single().on).isEqualTo(LocalDate.of(2026, 11, 10))
    }

    @Test
    fun `INV-AC-24782 every call resolves again, nothing is cached`() {
        val seen = mutableListOf<Seen>()
        var stored = "14"
        val reader = PolicyTenantLinkValidity(reader(seen) { listOf(custom(stored)) }, tenants, clock)
        assertThat(reader.days()).isEqualTo(14L)
        stored = "3"
        assertThat(reader.days()).isEqualTo(3L)
        assertThat(seen).hasSize(2)
    }
}
