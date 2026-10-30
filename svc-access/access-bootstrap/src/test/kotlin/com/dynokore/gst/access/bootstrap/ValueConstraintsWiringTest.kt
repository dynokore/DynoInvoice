package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.breakglass.BreakGlassPairConstraints
import com.dynokore.gst.access.adapters.landing.RolePrecedenceConstraints
import com.dynokore.gst.access.adapters.separation.ConflictingPairsConstraints
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/** The chain `setPolicy` asks of access-svc carries the check of VP-82 (INV-T-13495, INV-AC-13494). */
class ValueConstraintsWiringTest {
    private val catalogue = RoleCatalogue(Role.entries.toSet())
    private val mapper = JsonMapper()
    private val chain: ValueConstraints =
        AgentTenancyUseCases().valueConstraints(catalogue, mapper, DSL.using(SQLDialect.POSTGRES))
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val target = SettingTarget(tenant, ScopeRef(ScopeLevel.TENANT, tenant.toString()), null)
    private val today = LocalDate.of(2027, 1, 1)

    private fun check(
        constraints: ValueConstraints,
        key: String,
        selected: List<SelectedOption>,
    ) = constraints.check(target, VariationPointKey(key), selected, today)

    private fun pairs(json: String) = listOf(SelectedOption("CONFLICTING_PAIRS", json))

    @Test
    fun `INV-AC-13494 the chain accepts a valid VP-82 value`() {
        val selected = pairs("""[{"first":"tenant.admin","second":"tax.operator"}]""")
        assertThat(check(chain, "VP-82", selected)).isEqualTo(ConstraintVerdict.Accept)
    }

    @Test
    fun `INV-AC-13494 the chain refuses a VP-82 pair of two equal roles`() {
        val selected = pairs("""[{"first":"tenant.admin","second":"tenant.admin"}]""")
        assertThat(check(chain, "VP-82", selected))
            .isEqualTo(ConstraintVerdict.Refuse(ConflictingPairsConstraints.SAME_ROLE))
    }

    @Test
    fun `INV-AC-13494 the chain gives no verdict for VP-81`() {
        val selected = pairs("""[{"first":"tenant.admin","second":"tax.operator"}]""")
        assertThat(check(chain, "VP-81", selected)).isNull()
    }

    @Test
    fun `INV-AC-13494 the chain gives for VP-73 and VP-08 the verdicts of their own constraints`() {
        val precedence = RolePrecedenceConstraints(catalogue, mapper)
        val breakGlass = BreakGlassPairConstraints(catalogue, mapper)
        val vp73 =
            listOf(
                listOf(SelectedOption("TENANT_PRECEDENCE", """["tax.approver","tenant.admin"]""")),
                listOf(SelectedOption("TENANT_PRECEDENCE", """["tenant.admins"]""")),
            )
        vp73.forEach { assertThat(check(chain, "VP-73", it)).isEqualTo(check(precedence, "VP-73", it)) }
        val vp08 =
            listOf(
                listOf(SelectedOption("OTHER_NAMED_PAIR", """{"pair":["tenant.admin","tax.operator"]}""")),
                listOf(SelectedOption("OTHER_NAMED_PAIR", "not json")),
            )
        vp08.forEach { assertThat(check(chain, "VP-08", it)).isEqualTo(check(breakGlass, "VP-08", it)) }
    }
}
