package com.dynokore.gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-AC-10640: the operation-to-roles map, asserted through the published API only. */
class OperationRolesTest {
    private val forms = listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)

    @Test
    fun `INV-AC-10640 the map equals the cells of RolePermissions in every form`() {
        for (form in forms) {
            val cells = RolePermissions.cells(form)
            val allKeys = cells.values.flatMap { it.keys }.toSet()
            assertEquals(allKeys, OperationRoles.keys(form), "keys $form")
            for (key in allKeys) {
                val expected = cells.mapNotNull { (role, byKey) -> byKey[key]?.let { role to it } }.toMap()
                assertEquals(expected, OperationRoles.rolesFor(form, key), "$form $key")
            }
        }
    }

    @Test
    fun `INV-AC-10640 a key held by one form only`() {
        assertEquals(
            mapOf("platform.engineer" to RoleAllowance.PERMITTED),
            OperationRoles.rolesFor(DeploymentForm.SAAS, "regulatory-pack.activate"),
        )
        assertTrue(OperationRoles.rolesFor(DeploymentForm.PRIVATE, "regulatory-pack.activate").isEmpty())
    }

    @Test
    fun `INV-AC-10640 both allowances on doc view content`() {
        val roles = OperationRoles.rolesFor(DeploymentForm.SAAS, "doc.view.content")
        assertEquals(RoleAllowance.WITHIN_SCOPE, roles["agent.operator"])
        assertEquals(RoleAllowance.PERMITTED, roles["tax.operator"])
    }

    @Test
    fun `INV-AC-10640 a role name offered as a key has no roles`() {
        assertTrue(OperationRoles.rolesFor(DeploymentForm.SAAS, "deployment.operator").isEmpty())
    }

    private fun assertEmptyInBoth(key: String) {
        for (form in forms) assertTrue(OperationRoles.rolesFor(form, key).isEmpty(), "'$key' in $form")
    }

    @Test
    fun `INV-AC-10640 look-alike trailing space`() = assertEmptyInBoth("doc.view.content ")

    @Test
    fun `INV-AC-10640 look-alike upper case`() = assertEmptyInBoth("Doc.View.Content")

    @Test
    fun `INV-AC-10640 look-alike prefix`() = assertEmptyInBoth("doc.view")

    @Test
    fun `INV-AC-10640 look-alike suffix`() = assertEmptyInBoth("doc.view.content2")

    @Test
    fun `INV-AC-10640 look-alike entity admin`() = assertEmptyInBoth("entity.admin")
}
