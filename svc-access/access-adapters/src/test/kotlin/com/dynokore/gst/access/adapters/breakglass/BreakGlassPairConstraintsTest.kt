package com.dynokore.gst.access.adapters.breakglass

import com.dynokore.gst.access.application.breakglass.BREAK_GLASS_UNLOCK
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/** VP-08 refuses at store time a pair that names a role not holding break-glass.unlock (INV-T-24930). */
class BreakGlassPairConstraintsTest {
    private val catalogue = RoleCatalogue(Role.entries.toSet())
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val target = SettingTarget(tenant, ScopeRef(ScopeLevel.TENANT, tenant.toString()), null)

    private fun matrixOf(vararg cells: Pair<Role, Allowance>) =
        PermissionMatrix(
            listOf(PermissionDefinition(BREAK_GLASS_UNLOCK, PermissionKind.WRITE)),
            cells.groupBy({ it.first }, { it.second }).mapValues { mapOf(BREAK_GLASS_UNLOCK to it.value.single()) },
        )

    private val saas =
        matrixOf(
            Role.TENANT_ADMIN to Allowance.PERMITTED,
            Role.TAX_APPROVER to Allowance.PERMITTED,
        )

    private fun verdict(
        matrix: PermissionMatrix,
        value: String,
        key: String = "VP-08",
    ) = BreakGlassPairConstraints(catalogue, JsonMapper(), matrix)
        .check(
            target,
            VariationPointKey(key),
            listOf(SelectedOption("OTHER_NAMED_PAIR", value)),
            LocalDate.of(2027, 1, 1),
        )

    private fun pair(
        a: String,
        b: String,
    ) = """{"pair":["$a","$b"]}"""

    private fun refuse(vararg named: String) =
        ConstraintVerdict.Refuse("the pair names role ${named.single()}, which does not hold break-glass.unlock")

    @Test
    fun `INV-AC-24929 two holders are accepted`() {
        assertThat(verdict(saas, pair("tenant.admin", "tax.approver"))).isEqualTo(ConstraintVerdict.Accept)
    }

    @Test
    fun `INV-AC-24929 the same holder twice is accepted`() {
        assertThat(verdict(saas, pair("tenant.admin", "tenant.admin"))).isEqualTo(ConstraintVerdict.Accept)
    }

    @Test
    fun `INV-AC-24929 tax operator is refused by name`() {
        assertThat(verdict(saas, pair("tenant.admin", "tax.operator"))).isEqualTo(refuse("tax.operator"))
    }

    @Test
    fun `INV-AC-24929 auditor as first role is refused by name`() {
        assertThat(verdict(saas, pair("auditor", "tax.approver"))).isEqualTo(refuse("auditor"))
    }

    @Test
    fun `INV-AC-24929 deployment operator is refused in SAAS and accepted with the PRIVATE cell`() {
        assertThat(verdict(saas, pair("deployment.operator", "tax.approver"))).isEqualTo(refuse("deployment.operator"))
        val private = matrixOf(*saasCells(), Role.DEPLOYMENT_OPERATOR to Allowance.PERMITTED)
        assertThat(verdict(private, pair("deployment.operator", "tax.approver"))).isEqualTo(ConstraintVerdict.Accept)
    }

    private fun saasCells() =
        arrayOf(Role.TENANT_ADMIN to Allowance.PERMITTED, Role.TAX_APPROVER to Allowance.PERMITTED)

    @Test
    fun `INV-AC-24929 the matrix decides so a permitted tax operator is accepted`() {
        val matrix = matrixOf(*saasCells(), Role.TAX_OPERATOR to Allowance.PERMITTED)
        assertThat(verdict(matrix, pair("tenant.admin", "tax.operator"))).isEqualTo(ConstraintVerdict.Accept)
    }

    @Test
    fun `INV-AC-24929 a role with no cell is refused`() {
        val matrix = matrixOf(Role.TENANT_ADMIN to Allowance.PERMITTED)
        assertThat(verdict(matrix, pair("tenant.admin", "tax.approver"))).isEqualTo(refuse("tax.approver"))
    }

    @Test
    fun `INV-AC-24929 a WITHIN_SCOPE cell does not count as holding`() {
        val matrix = matrixOf(Role.TENANT_ADMIN to Allowance.PERMITTED, Role.TAX_APPROVER to Allowance.WITHIN_SCOPE)
        assertThat(verdict(matrix, pair("tenant.admin", "tax.approver"))).isEqualTo(refuse("tax.approver"))
    }

    @Test
    fun `INV-AC-24929 the earlier refusals are unchanged and decided first`() {
        assertThat(verdict(saas, "not json"))
            .isEqualTo(ConstraintVerdict.Refuse("the pair must name exactly two roles"))
        assertThat(verdict(saas, """{"pair":["tenant.admin","tax.approver","auditor"]}"""))
            .isEqualTo(ConstraintVerdict.Refuse("the pair must name exactly two roles"))
        assertThat(verdict(saas, pair("tenant.admin", "no.such.role")))
            .isEqualTo(ConstraintVerdict.Refuse("the pair names a role that is not in this installation's catalogue"))
        // a non-holder next to an unknown role: the catalogue message comes first
        assertThat(verdict(saas, pair("tax.operator", "no.such.role")))
            .isEqualTo(ConstraintVerdict.Refuse("the pair names a role that is not in this installation's catalogue"))
        assertThat(verdict(saas, """{"pair":["tax.operator","auditor","tenant.admin"]}"""))
            .isEqualTo(ConstraintVerdict.Refuse("the pair must name exactly two roles"))
    }

    @Test
    fun `INV-AC-24929 another variation point gets no verdict`() {
        assertThat(verdict(saas, pair("tenant.admin", "tax.operator"), "VP-09")).isNull()
    }
}
