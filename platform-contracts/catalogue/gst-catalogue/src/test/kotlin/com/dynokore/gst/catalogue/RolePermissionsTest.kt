package com.dynokore.gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-T-7987: the published library serves the role-by-permission table; asserted through that API only. */
class RolePermissionsTest {
    private val saas = DeploymentForm.SAAS
    private val privateForm = DeploymentForm.PRIVATE
    private val both = listOf(saas, privateForm)
    private val y = RoleAllowance.PERMITTED

    private fun cell(
        form: DeploymentForm,
        role: String,
        key: String,
    ) = RolePermissions.cells(form)[role]?.get(key)

    /** One row of the required-cell table; every other (role, form) is DENIED on that key. */
    private data class Req(
        val key: String,
        val kind: PermissionKind,
        val role: String,
        val allowance: RoleAllowance,
        val forms: List<DeploymentForm>,
    )

    private val required =
        listOf(
            Req("user.manage", PermissionKind.WRITE, "tenant.admin", y, both),
            Req("ap-credential.manage", PermissionKind.WRITE, "tenant.admin", y, both),
            Req("ap-credential.manage", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
            Req("entity-config.manage", PermissionKind.WRITE, "entity.admin", y, both),
            Req("entity-config.confirm", PermissionKind.WRITE, "entity.admin", y, both),
            Req("entity-config.confirm", PermissionKind.WRITE, "tenant.admin", y, both),
            Req("entity-config.confirm", PermissionKind.WRITE, "tax.approver", y, both),
        ) +
            listOf("tenant.admin", "tax.operator", "tax.approver", "finance.viewer", "auditor").map {
                Req("doc.view.content", PermissionKind.READ, it, y, both)
            } +
            listOf(
                Req("doc.view.content", PermissionKind.READ, "agent.operator", RoleAllowance.WITHIN_SCOPE, both),
                Req("support-bundle.export", PermissionKind.READ, "deployment.operator", y, listOf(privateForm)),
                Req(
                    "regulatory-pack.import-verify",
                    PermissionKind.WRITE,
                    "deployment.operator",
                    y,
                    listOf(privateForm),
                ),
                Req("regulatory-pack.import-verify", PermissionKind.WRITE, "tax.approver", y, listOf(privateForm)),
                Req("regulatory-pack.arm", PermissionKind.WRITE, "tax.approver", y, listOf(privateForm)),
                Req("regulatory-pack.activate", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
                Req("retention.delete.request", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
                Req("retention.delete.request", PermissionKind.WRITE, "deployment.operator", y, listOf(privateForm)),
                Req("retention.delete.approve", PermissionKind.WRITE, "security.officer", y, listOf(saas)),
                Req("retention.delete.approve", PermissionKind.WRITE, "tax.approver", y, listOf(privateForm)),
            ) +
            listOf("elevation.decide", "access-review.run", "privacy-request.handle").map {
                Req(it, PermissionKind.WRITE, "security.officer", y, both)
            } +
            listOf("tenant.admin", "tax.approver", "auditor", "security.officer").map {
                Req("audit-log.view", PermissionKind.READ, it, y, both)
            } +
            Req("audit-log.view", PermissionKind.READ, "platform.engineer", y, listOf(saas))

    @Test
    fun `every required cell is served and every other cell of a required key is denied`() {
        val keys = required.map { it.key }.toSet()
        keys.forEach { key ->
            val reqs = required.filter { it.key == key }
            assertEquals(reqs.first().kind, RolePermissions.kind(key), key)
            both.forEach { form ->
                val expected = reqs.filter { form in it.forms }.associate { it.role to it.allowance }
                val actual =
                    RolePermissions.cells(form).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
                assertEquals(expected, actual, "$key $form")
            }
        }
    }

    @Test
    fun `private form holds the deployment operator and none of the four SaaS-only roles`() {
        val roles = RolePermissions.roles(privateForm)
        assertTrue("deployment.operator" in roles)
        listOf("platform.support", "platform.engineer", "release.manager", "commercial.admin")
            .forEach { assertFalse(it in roles, it) }
    }

    @Test
    fun `SaaS form does not hold the deployment operator`() {
        assertFalse("deployment.operator" in RolePermissions.roles(saas))
        assertEquals(18, RolePermissions.roles(saas).size)
        assertEquals(15, RolePermissions.roles(privateForm).size)
    }

    @Test
    fun `cells are served for exactly the roles of the form`() {
        both.forEach { assertEquals(RolePermissions.roles(it), RolePermissions.cells(it).keys, it.name) }
    }

    @Test
    fun `a role or key that is not in the table gives no cell and no kind`() {
        assertNull(cell(saas, "tenant.admins", "user.manage"))
        assertNull(cell(saas, "tenant.admin", "User.manage"))
        assertNull(cell(saas, "tenant.admin", "user.manage2"))
        assertNull(cell(saas, "deployment.operator", "support-bundle.export"))
        assertNull(RolePermissions.kind("user.manage2"))
        assertNull(RolePermissions.kind(""))
        assertNull(RolePermissions.cells(saas)["x"])
    }

    @Test
    fun `an auditor holds no cell on a WRITE key in either form`() {
        both.forEach { form ->
            RolePermissions
                .cells(form)
                .getValue("auditor")
                .keys
                .forEach { assertEquals(PermissionKind.READ, RolePermissions.kind(it), it) }
        }
    }
}
