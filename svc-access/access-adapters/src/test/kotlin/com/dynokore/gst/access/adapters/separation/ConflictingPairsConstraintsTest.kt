package com.dynokore.gst.access.adapters.separation

import com.dynokore.gst.access.application.separation.RolePair
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
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
 * The check access-svc gives `setPolicy` for VP-82 (INV-T-13495, INV-AC-13494, INV-REQ-9271): a value is accepted only
 * if [VariationPointConflictingPairs] can read it; every other key gets no verdict.
 */
class ConflictingPairsConstraintsTest {
    private val mapper = ObjectMapper()
    private val all = RoleCatalogue(Role.entries.toSet())
    private val withoutSupport = RoleCatalogue(Role.entries.toSet() - Role.PLATFORM_SUPPORT)
    private val constraints = ConflictingPairsConstraints(all, mapper)
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val today = LocalDate.of(2027, 1, 1)
    private val clock = Clock.fixed(Instant.parse("2026-10-21T17:30:00Z"), ZoneOffset.UTC)

    private val target = SettingTarget(tenant, ScopeRef(ScopeLevel.TENANT, tenant.toString()), null)

    private fun check(
        selected: List<SelectedOption>,
        key: String = "VP-82",
        using: ConflictingPairsConstraints = constraints,
    ) = using.check(target, VariationPointKey(key), selected, today)

    private fun reader(selected: List<SelectedOption>): VariationPointConflictingPairs {
        val policy =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PolicyReader::class.java)) { _, method, args ->
                check(method.name.startsWith("resolve")) { "the reader of VP-82 only resolves" }
                ResolvedSetting(
                    VariationPointKey(args[0] as String),
                    args[2] as LocalDate,
                    selected,
                    PolicySource.CatalogueDefault,
                )
            } as PolicyReader
        val tenants =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, _, _ ->
                tenant.value
            } as TenantContext
        return VariationPointConflictingPairs(policy, tenants, clock, mapper)
    }

    private fun readerResult(selected: List<SelectedOption>): Result<Set<RolePair>> =
        runCatching { reader(selected).current() }

    private fun pair(
        first: String,
        second: String,
    ) = """{"first":"$first","second":"$second"}"""

    private fun pairs(json: String?) = listOf(SelectedOption("CONFLICTING_PAIRS", json))

    private class Row(
        val name: String,
        val selected: List<SelectedOption>,
        val accepted: Set<RolePair>?,
        val reason: String?,
        val catalogue: RoleCatalogue? = null,
    )

    private val admin = "tenant.admin"
    private val operator = "tax.operator"
    private val approver = "tax.approver"
    private val adminOperator = RolePair.of(Role.TENANT_ADMIN, Role.TAX_OPERATOR)

    private fun accepted(
        name: String,
        selected: List<SelectedOption>,
        expected: Set<RolePair>,
    ) = Row(name, selected, expected, null)

    private fun refused(
        name: String,
        selected: List<SelectedOption>,
        reason: String,
        catalogue: RoleCatalogue? = null,
    ) = Row(name, selected, null, reason, catalogue)

    private val parityRows =
        listOf(
            accepted("NO_EXTRA_PAIRS without value", listOf(SelectedOption("NO_EXTRA_PAIRS")), emptySet()),
            accepted("one pair", pairs("[${pair(admin, operator)}]"), setOf(adminOperator)),
            accepted(
                "two pairs",
                pairs("[${pair(admin, operator)},${pair(admin, approver)}]"),
                setOf(adminOperator, RolePair.of(Role.TENANT_ADMIN, Role.TAX_APPROVER)),
            ),
            accepted(
                "the same pair in both orders",
                pairs("[${pair(admin, operator)},${pair(operator, admin)}]"),
                setOf(adminOperator),
            ),
            accepted(
                "the same pair twice",
                pairs("[${pair(admin, operator)},${pair(admin, operator)}]"),
                setOf(adminOperator),
            ),
            refused(
                "unknown option key NO_EXTRA_PAIR",
                listOf(SelectedOption("NO_EXTRA_PAIR")),
                ConflictingPairsConstraints.UNKNOWN_OPTION,
            ),
            refused(
                "key conflicting_pairs",
                listOf(SelectedOption("conflicting_pairs", "[${pair(admin, operator)}]")),
                ConflictingPairsConstraints.UNKNOWN_OPTION,
            ),
            refused(
                "CONFLICTING_PAIRS with a trailing space",
                listOf(SelectedOption("CONFLICTING_PAIRS ", "[${pair(admin, operator)}]")),
                ConflictingPairsConstraints.UNKNOWN_OPTION,
            ),
            refused("empty selection", emptyList(), ConflictingPairsConstraints.ONE_OPTION),
            refused(
                "two selected options",
                listOf(
                    SelectedOption("NO_EXTRA_PAIRS"),
                    SelectedOption("CONFLICTING_PAIRS", "[${pair(admin, operator)}]"),
                ),
                ConflictingPairsConstraints.ONE_OPTION,
            ),
            refused("CONFLICTING_PAIRS without value", pairs(null), ConflictingPairsConstraints.LIST_REQUIRED),
            refused("empty list", pairs("[]"), ConflictingPairsConstraints.LIST_REQUIRED),
            refused("list that is an object", pairs(pair(admin, operator)), ConflictingPairsConstraints.LIST_REQUIRED),
            refused("pair of two equal roles", pairs("[${pair(admin, admin)}]"), ConflictingPairsConstraints.SAME_ROLE),
            refused(
                "name Tenant.Admin",
                pairs("[${pair("Tenant.Admin", operator)}]"),
                ConflictingPairsConstraints.UNKNOWN_ROLE,
            ),
            refused(
                "name tenant.admins",
                pairs("[${pair("tenant.admins", operator)}]"),
                ConflictingPairsConstraints.UNKNOWN_ROLE,
            ),
            refused("element that is a string", pairs("[\"tenant.admin\"]"), ConflictingPairsConstraints.PAIR_SHAPE),
            refused(
                "pair with a third field",
                pairs("""[{"first":"$admin","second":"$operator","third":"$approver"}]"""),
                ConflictingPairsConstraints.PAIR_SHAPE,
            ),
            refused(
                "pair with a missing field",
                pairs("""[{"first":"$admin"}]"""),
                ConflictingPairsConstraints.PAIR_SHAPE,
            ),
            refused(
                "non-string value of first",
                pairs("""[{"first":1,"second":"$operator"}]"""),
                ConflictingPairsConstraints.NOT_A_STRING,
            ),
            refused("text that is not JSON", pairs("not json"), ConflictingPairsConstraints.NOT_JSON),
        )

    private val stricterRows =
        listOf(
            refused(
                "NO_EXTRA_PAIRS with a value",
                listOf(SelectedOption("NO_EXTRA_PAIRS", "[${pair(admin, operator)}]")),
                ConflictingPairsConstraints.NO_VALUE,
            ),
            refused(
                "role outside the catalogue",
                pairs("[${pair("platform.support", operator)}]"),
                ConflictingPairsConstraints.NOT_IN_CATALOGUE,
                withoutSupport,
            ),
        )

    @Test
    fun `INV-AC-13494 every row of the table gets its verdict and the reader agrees one way`() {
        parityRows.forEach { row ->
            val verdict = check(row.selected)
            val read = readerResult(row.selected)
            if (row.accepted != null) {
                assertThat(verdict).`as`(row.name).isEqualTo(ConstraintVerdict.Accept)
                assertThat(read.isSuccess).`as`("reader ${row.name}").isTrue()
                assertThat(read.getOrThrow()).`as`("pairs ${row.name}").isEqualTo(row.accepted)
            } else {
                assertThat(verdict).`as`(row.name).isEqualTo(ConstraintVerdict.Refuse(row.reason!!))
            }
        }
    }

    @Test
    fun `INV-AC-13494 every row the reader cannot read is refused`() {
        parityRows.forEach { row ->
            if (readerResult(row.selected).isFailure) {
                assertThat(check(row.selected)).`as`(row.name).isInstanceOf(ConstraintVerdict.Refuse::class.java)
            }
        }
    }

    @Test
    fun `INV-AC-13494 the rows stricter than the reader are refused on purpose`() {
        stricterRows.forEach { row ->
            assertThat(readerResult(row.selected).isSuccess).`as`("reader ${row.name}").isTrue()
            val using = row.catalogue?.let { ConflictingPairsConstraints(it, mapper) } ?: constraints
            assertThat(
                check(row.selected, using = using),
            ).`as`(row.name).isEqualTo(ConstraintVerdict.Refuse(row.reason!!))
        }
    }

    @Test
    fun `INV-AC-13494 a role missing from the catalogue is refused by the second instance only`() {
        val selected = pairs("[${pair("platform.support", operator)}]")
        assertThat(check(selected)).isEqualTo(ConstraintVerdict.Accept)
        assertThat(check(selected, using = ConflictingPairsConstraints(withoutSupport, mapper)))
            .isEqualTo(ConstraintVerdict.Refuse(ConflictingPairsConstraints.NOT_IN_CATALOGUE))
    }

    @Test
    fun `INV-AC-13494 every other key gets no verdict`() {
        val valid = pairs("[${pair(admin, operator)}]")
        listOf("VP-73", "VP-81", "VP-08", "VP-74", "VP-820", "VP-082").forEach { key ->
            assertThat(check(valid, key)).`as`(key).isNull()
        }
    }

    @Test
    fun `INV-AC-13494 the look-alike keys vp-82 and VP-82 with a space cannot reach check`() {
        listOf("vp-82", "VP-82 ").forEach { key ->
            assertThatThrownBy { VariationPointKey(key) }.`as`(key).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `INV-AC-13494 check never throws on a value that does not parse`() {
        listOf("{", "[", "[[", "null", "\"x\"", "[null]", "[{}]", "1e999999999999").forEach { json ->
            assertThat(check(pairs(json))).`as`(json).isInstanceOf(ConstraintVerdict.Refuse::class.java)
        }
    }
}
