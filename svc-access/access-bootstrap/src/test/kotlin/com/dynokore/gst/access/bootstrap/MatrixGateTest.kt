package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

private typealias Cells = Map<String, Map<String, Allowance>>

/**
 * The matrix gate (INV-AC-7945, INV-Q-7979 A): every cell of the role-by-permission table of the gst-catalogue is
 * compared with the same cell of the matrix the loader builds, in both directions, for both forms. The probes run on
 * copies of the exported map.
 */
class MatrixGateTest {
    private fun expected(form: DeploymentForm): Cells =
        RolePermissions.cells(form).mapValues { (_, cells) -> cells.mapValues { allowanceOf(it.value) } }

    private fun allowanceOf(allowance: RoleAllowance) =
        when (allowance) {
            RoleAllowance.PERMITTED -> Allowance.PERMITTED
            RoleAllowance.WITHIN_SCOPE -> Allowance.WITHIN_SCOPE
        }

    private fun exported(form: DeploymentForm): Cells = PermissionMatrixLoader.load(form).export()

    /** The differing cells, each naming role, permission and form; a cell on one side only counts as differing. */
    private fun gate(
        form: DeploymentForm,
        actual: Cells,
    ): List<String> {
        val catalogue = expected(form)
        val differing = mutableListOf<String>()
        for (role in catalogue.keys + actual.keys) {
            val want = catalogue[role].orEmpty()
            val have = actual[role].orEmpty()
            for (permission in want.keys + have.keys) {
                if (want[permission] != have[permission]) {
                    differing +=
                        "role $role permission $permission form $form: " +
                        "catalogue ${want[permission]} matrix ${have[permission]}"
                }
            }
        }
        return differing
    }

    private fun mutable(cells: Cells): MutableMap<String, MutableMap<String, Allowance>> =
        cells.mapValues { it.value.toMutableMap() }.toMutableMap()

    @Test
    fun `INV-AC-7945 the matrix equals the catalogue table cell by cell for both forms`() {
        DeploymentForm.entries.forEach { form ->
            assertThat(RolePermissions.cells(form)).describedAs("table of $form").isNotEmpty()
            assertThat(gate(form, exported(form))).describedAs("differing cells of $form").isEmpty()
        }
    }

    @Test
    fun `INV-AC-7945 probe 1 - tenant admin granted the cells entity admin holds fails naming the cell`() {
        val copy = mutable(exported(DeploymentForm.SAAS))
        val entityCells = copy.getValue("entity.admin").filterKeys { it !in copy.getValue("tenant.admin") }
        assertThat(entityCells).isNotEmpty()
        copy.getValue("tenant.admin").putAll(entityCells)
        val failures = gate(DeploymentForm.SAAS, copy)
        assertThat(failures).hasSize(entityCells.size)
        entityCells.keys.forEach { key ->
            assertThat(failures).anyMatch { it.startsWith("role tenant.admin permission $key form SAAS") }
        }
    }

    @Test
    fun `INV-AC-7945 probe 2 - one cell removed from one role fails naming the cell`() {
        val copy = mutable(exported(DeploymentForm.SAAS))
        val permission = copy.getValue("tenant.admin").keys.first()
        copy.getValue("tenant.admin").remove(permission)
        assertThat(gate(DeploymentForm.SAAS, copy))
            .singleElement()
            .asString()
            .startsWith("role tenant.admin permission $permission form SAAS")
    }

    @Test
    fun `INV-AC-7945 probe 3 - a role renamed to a look-alike fails on every cell of both names`() {
        val copy = mutable(exported(DeploymentForm.SAAS))
        val row = copy.remove("tenant.admin")!!
        copy["tenant.admins"] = row
        val failures = gate(DeploymentForm.SAAS, copy)
        assertThat(failures).hasSize(row.size * 2)
        assertThat(failures).anyMatch { it.startsWith("role tenant.admin permission ") }
        assertThat(failures).anyMatch { it.startsWith("role tenant.admins permission ") }
    }

    @Test
    fun `INV-AC-7945 probe 4 - a permission key the catalogue lacks fails naming the cell`() {
        val copy = mutable(exported(DeploymentForm.SAAS))
        copy.getValue("tenant.admin")["user.manage.extra"] = Allowance.PERMITTED
        assertThat(gate(DeploymentForm.SAAS, copy))
            .singleElement()
            .asString()
            .startsWith("role tenant.admin permission user.manage.extra form SAAS")
    }

    @Test
    fun `INV-AC-7945 probe 5 - a permission key the catalogue has, removed from every role, fails naming each cell`() {
        val copy = mutable(exported(DeploymentForm.SAAS))
        val key = "user.manage"
        val holders = copy.filterValues { key in it }.keys
        assertThat(holders).isNotEmpty()
        copy.values.forEach { it.remove(key) }
        val failures = gate(DeploymentForm.SAAS, copy)
        assertThat(failures).hasSize(holders.size)
        holders.forEach { role ->
            assertThat(failures).anyMatch { it.startsWith("role $role permission $key form SAAS") }
        }
    }

    @Test
    fun `INV-AC-7945 probe 6 - a cell of deployment operator in the SAAS export fails naming the cell`() {
        assertThat(expected(DeploymentForm.SAAS)["deployment.operator"].orEmpty()).isEmpty()
        val copy = mutable(exported(DeploymentForm.SAAS))
        copy.getOrPut("deployment.operator") { mutableMapOf() }["support-bundle.export"] = Allowance.PERMITTED
        assertThat(gate(DeploymentForm.SAAS, copy))
            .singleElement()
            .asString()
            .startsWith("role deployment.operator permission support-bundle.export form SAAS")
    }

    @Test
    fun `the loader stops the start-up on a role key it cannot map and on a permission without a kind`() {
        val unknownRole =
            object : CatalogueTable by CatalogueTable.PUBLISHED {
                override fun cells(form: DeploymentForm) =
                    CatalogueTable.PUBLISHED.cells(form) + ("tenant.admins" to emptyMap())

                override fun roles(form: DeploymentForm) = CatalogueTable.PUBLISHED.roles(form) + "tenant.admins"
            }
        assertThatThrownBy { PermissionMatrixLoader.load(DeploymentForm.SAAS, unknownRole) }
            .hasMessageContaining("tenant.admins")
        assertThatThrownBy { PermissionMatrixLoader.roleCatalogue(DeploymentForm.SAAS, unknownRole) }
            .hasMessageContaining("tenant.admins")
        val noKind =
            object : CatalogueTable by CatalogueTable.PUBLISHED {
                override fun kind(permissionKey: String) = null
            }
        assertThatThrownBy { PermissionMatrixLoader.load(DeploymentForm.SAAS, noKind) }
            .hasMessageContaining("no kind")
    }

    @Test
    fun `the production matrix is not empty`() {
        assertThat(
            PermissionMatrixLoader.load(DeploymentForm.SAAS).export(),
        ).isNotEqualTo(PermissionMatrix.EMPTY.export())
    }
}
