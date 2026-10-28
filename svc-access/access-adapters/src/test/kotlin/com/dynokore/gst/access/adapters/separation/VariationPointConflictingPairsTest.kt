package com.dynokore.gst.access.adapters.separation

import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.application.separation.RolePair
import com.dynokore.gst.access.domain.role.Role
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
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The reader of VP-82 (INV-T-12356, INV-AC-12355, INV-REQ-9271): the value of the Asia/Singapore date of the service
 * clock, mapped by string equality; anything else throws (an unknown key, an empty selection, equal roles, a name that is no role).
 * The stand-in reader answers as config-resolver does: the latest value whose
 * effective date is not after the date asked for, else the catalogue default.
 */
class VariationPointConflictingPairsTest {
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
            check(method.name.startsWith("resolve")) { "the reader of VP-82 only resolves" }
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
                effective?.selected ?: listOf(SelectedOption("NO_EXTRA_PAIRS")),
                PolicySource.CatalogueDefault,
            )
        } as PolicyReader

    private val tenants: TenantContext =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, method, _ ->
            check(method.name.startsWith("current")) { "the reader only asks for the tenant" }
            // the value class TenantId is returned unboxed as its UUID by the mangled method
            tenant.value
        } as TenantContext

    private fun policy(reader: PolicyReader): ConflictingRolePairs =
        VariationPointConflictingPairs(reader, tenants, clock, ObjectMapper())

    private fun option(key: String) = listOf(SelectedOption(key))

    private fun pairs(json: String) = listOf(SelectedOption("CONFLICTING_PAIRS", json))

    private val effective = LocalDate.parse("2026-10-01")
    private val one = """[{"first":"tenant.admin","second":"tax.operator"}]"""

    private fun current(selected: List<SelectedOption>) = policy(reader(listOf(Stored(effective, selected)))).current()

    @Test
    fun `INV-AC-12355 a value effective on the Singapore date is returned and one effective the day after is not`() {
        val today = LocalDate.parse("2026-10-22")
        val asked = mutableListOf<Pair<VariationPointKey, LocalDate>>()
        val expected = setOf(RolePair.of(Role.TENANT_ADMIN, Role.TAX_OPERATOR))
        assertThat(policy(reader(listOf(Stored(today, pairs(one))), asked)).current()).isEqualTo(expected)
        assertThat(asked).containsExactly(VariationPointKey("VP-82") to today)
        assertThat(policy(reader(listOf(Stored(today.plusDays(1), pairs(one))))).current()).isEmpty()
    }

    @Test
    fun `INV-AC-12355 NO_EXTRA_PAIRS and the default give the empty set`() {
        assertThat(current(option("NO_EXTRA_PAIRS"))).isEmpty()
        assertThat(policy(reader(emptyList())).current()).isEmpty()
    }

    @Test
    fun `INV-AC-12355 a listed pair is unordered and a repeated pair in the other order is one pair`() {
        val json =
            """[{"first":"tenant.admin","second":"tax.operator"},""" +
                """{"first":"tax.operator","second":"tenant.admin"}]"""
        assertThat(current(pairs(json))).containsExactly(RolePair.of(Role.TAX_OPERATOR, Role.TENANT_ADMIN))
    }

    @Test
    fun `INV-AC-12355 a key that is none of the two throws and is never mapped to another value`() {
        listOf("NO_EXTRA_PAIR", "conflicting_pairs", "CONFLICTING_PAIRS ", " NO_EXTRA_PAIRS", "NO_EXTRA_PAIRS\n", "")
            .forEach { key ->
                assertThatThrownBy { current(listOf(SelectedOption(key, one))) }
                    .describedAs("key '%s'", key)
                    .isInstanceOf(IllegalStateException::class.java)
            }
    }

    @Test
    fun `INV-AC-12355 an empty selection and two selected options throw`() {
        assertThatThrownBy { current(emptyList()) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { current(option("NO_EXTRA_PAIRS") + pairs(one)) }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `INV-AC-12355 a value that is no list of pairs of known roles throws`() {
        listOf(
            """[{"first":"tax.operator","second":"tax.operator"}]""",
            """[{"first":"tax.operator","second":"Tenant.Admin"}]""",
            """[{"first":"tax.operator","second":"tenant.admin "}]""",
            """[{"first":"tax.operator","second":"nobody"}]""",
            """[{"first":"tax.operator"}]""",
            """[{"first":"tax.operator","second":"tenant.admin","third":"viewer"}]""",
            """[{"first":"tax.operator","second":1}]""",
            """[["tax.operator","tenant.admin"]]""",
            """[]""",
            """{"first":"tax.operator","second":"tenant.admin"}""",
            """"tax.operator"""",
        ).forEach { json ->
            assertThatThrownBy {
                current(
                    pairs(json),
                )
            }.describedAs("value %s", json).isInstanceOf(RuntimeException::class.java)
        }
        assertThatThrownBy { current(listOf(SelectedOption("CONFLICTING_PAIRS"))) }
            .isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `INV-AC-12355 a failed read throws and no default is returned`() {
        val failing = reader(emptyList(), failure = IllegalStateException("store down"))
        assertThatThrownBy { policy(failing).current() }.hasMessage("store down")
    }
}
