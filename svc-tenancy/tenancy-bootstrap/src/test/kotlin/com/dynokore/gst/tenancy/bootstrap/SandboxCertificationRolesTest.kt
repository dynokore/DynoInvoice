package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.catalogue.PermissionKind
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The pinned gst-catalogue carries the permission key `sandbox-certification.record` with one role cell per form
 * (INV-T-18702). The role is named here only as the expected value; sets and maps are compared by equality.
 */
class SandboxCertificationRolesTest {
    private val key = "sandbox-certification.record"

    @Test
    fun `INV-AC-18701 integration_engineer alone holds sandbox-certification_record in both forms`() {
        DeploymentForm.entries.forEach { form ->
            assertThat(OperationRoles.rolesFor(form, key))
                .describedAs(form.name)
                .isEqualTo(mapOf("integration.engineer" to RoleAllowance.PERMITTED))
            listOf("sandbox-certification-record", "sandbox-certification.recorded", "Sandbox-certification.record")
                .forEach { assertThat(OperationRoles.rolesFor(form, it)).describedAs("$form $it").isEmpty() }
            assertThat(OperationRoles.rolesFor(form, "entity-config.manage").keys)
                .describedAs(form.name)
                .doesNotContain("integration.engineer")
        }
        assertThat(RolePermissions.kind(key)).isEqualTo(PermissionKind.WRITE)
    }
}
