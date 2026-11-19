package com.dynokore.gst.reconciliation.acceptance

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.PermissionKind
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-T-24867: the pinned gst-catalogue gives tax.approver the three reconciliation keys (ruling 2026-11-18, A). */
class ReconciliationPermissionCellsTest {
    private val keys = listOf("reconciliation.run", "reconciliation.annotate", "reconciliation.attest")

    private fun rolesWith(
        form: DeploymentForm,
        key: String,
        allowance: RoleAllowance,
    ): Set<String> =
        RolePermissions
            .cells(form)
            .filterValues { it[key] == allowance }
            .keys

    private fun check(
        form: DeploymentForm,
        allowance: RoleAllowance,
        expected: Set<String>,
    ) {
        keys.forEach { key -> rolesWith(form, key, allowance) shouldBe expected }
    }

    @Test
    fun `INV-AC-24865 SAAS PERMITTED roles are exactly tax_approver`() =
        check(DeploymentForm.SAAS, RoleAllowance.PERMITTED, setOf("tax.approver"))

    @Test
    fun `INV-AC-24865 PRIVATE PERMITTED roles are exactly tax_approver`() =
        check(DeploymentForm.PRIVATE, RoleAllowance.PERMITTED, setOf("tax.approver"))

    @Test
    fun `INV-AC-24865 SAAS WITHIN_SCOPE roles are exactly agent_operator`() =
        check(DeploymentForm.SAAS, RoleAllowance.WITHIN_SCOPE, setOf("agent.operator"))

    @Test
    fun `INV-AC-24865 PRIVATE WITHIN_SCOPE roles are exactly agent_operator`() =
        check(DeploymentForm.PRIVATE, RoleAllowance.WITHIN_SCOPE, setOf("agent.operator"))

    @Test
    fun `INV-AC-24865 the three keys are WRITE`() {
        keys.forEach { RolePermissions.kind(it) shouldBe PermissionKind.WRITE }
    }
}
