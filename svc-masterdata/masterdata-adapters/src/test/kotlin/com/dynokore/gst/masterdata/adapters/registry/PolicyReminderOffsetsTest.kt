package com.dynokore.gst.masterdata.adapters.registry

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
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-REQ-837, INV-REQ-2464 (VP-68): the option keys map to day lists, and any other option fails closed. */
class PolicyReminderOffsetsTest {
    private val tenant = TenantId(UUID.randomUUID())
    private var clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)

    private val tenants: TenantContext =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, method, _ ->
            // `TenantContext.current()` returns a value class, so its name is mangled and the raw UUID is returned
            check(method.name.startsWith("current")) { "unexpected call ${method.name}" }
            tenant.value
        } as TenantContext

    private fun offsets(
        key: String,
        value: String? = null,
        seen: MutableList<Triple<VariationPointKey, PolicyScope, LocalDate>> = mutableListOf(),
    ): PolicyReminderOffsets {
        val reader =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PolicyReader::class.java)) { _, method, args ->
                // a value-class parameter makes the name mangled and passes the raw text
                check(method.name.startsWith("resolve")) { "unexpected call ${method.name}" }
                val vp = args[0].let { if (it is String) VariationPointKey(it) else it as VariationPointKey }
                val scope = args[1] as PolicyScope
                val at = args[2] as LocalDate
                seen += Triple(vp, scope, at)
                ResolvedSetting(vp, at, listOf(SelectedOption(key, value)), PolicySource.CatalogueDefault)
            } as PolicyReader
        return PolicyReminderOffsets(reader, tenants, JsonMapper.builder().build(), clock)
    }

    @Test
    fun `option DAYS_14_3 gives the list 14 and 3, read for VP-68 at tenant scope on the transaction date`() {
        val seen = mutableListOf<Triple<VariationPointKey, PolicyScope, LocalDate>>()
        assertThat(offsets("DAYS_14_3", seen = seen).days()).isEqualTo(listOf(14, 3))
        assertThat(seen.single().first).isEqualTo(VariationPointKey("VP-68"))
        // PolicyScope has no value equality: its rendering names the scope levels and ids
        assertThat(
            seen
                .single()
                .second
                .toString()
                .substringBefore(" ("),
        ).isEqualTo(PolicyScope.tenant(tenant).toString().substringBefore(" ("))
        assertThat(seen.single().second.toString()).startsWith("TENANT:${tenant.value} ")
        assertThat(seen.single().third).isEqualTo(LocalDate.of(2026, 11, 10))
    }

    @Test
    fun `option CUSTOM_DAYS gives the integer list of its value`() {
        assertThat(offsets("CUSTOM_DAYS", "[10,3]").days()).isEqualTo(listOf(10, 3))
    }

    @Test
    fun `a look-alike option key, an empty key and an unknown key fail with an error`() {
        listOf("DAYS_14_3X", "days_14_3", "", " DAYS_14_3", "CUSTOM_DAYS2").forEach {
            assertThatThrownBy { offsets(it, "[10,3]").days() }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `a custom value that is not a non-empty list of positive integers fails`() {
        listOf("[]", "10", "{\"a\":1}", "[0]", "[\"10\"]", "[3.5]", "[-3]").forEach {
            assertThatThrownBy { offsets("CUSTOM_DAYS", it).days() }.isInstanceOf(IllegalStateException::class.java)
        }
        assertThatThrownBy { offsets("CUSTOM_DAYS", null).days() }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the date given to the policy reader is the Singapore business date of the clock`() {
        listOf("2026-03-31T17:00:00Z" to LocalDate.of(2026, 4, 1), "2026-03-31T15:59:59Z" to LocalDate.of(2026, 3, 31))
            .forEach { (instant, expected) ->
                clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
                val seen = mutableListOf<Triple<VariationPointKey, PolicyScope, LocalDate>>()
                offsets("DAYS_14_3", seen = seen).days()
                assertThat(seen.single().third).isEqualTo(expected)
            }
    }
}
