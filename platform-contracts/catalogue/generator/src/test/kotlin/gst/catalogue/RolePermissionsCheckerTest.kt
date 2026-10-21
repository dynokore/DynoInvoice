package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** INV-T-7987: each probe is added alone to the parsed real file, and the checker names the role, key and form. */
class RolePermissionsCheckerTest {
    private val real = RolePermissionYamlParser.parse(File("../role-permissions/role-permissions.yaml"))

    private fun permission(
        table: RolePermissionTable,
        key: String,
    ) = table.permissions.first { it.key == key }

    private fun withPermission(
        key: String,
        change: (PermissionRow) -> PermissionRow,
    ) = real.copy(permissions = real.permissions.map { if (it.key == key) change(it) else it })

    private fun withRole(
        key: String,
        change: (RoleRow) -> RoleRow,
    ) = real.copy(roles = real.roles.map { if (it.key == key) change(it) else it })

    private fun assertReported(
        table: RolePermissionTable,
        vararg parts: String,
    ) {
        val violations = RolePermissionsChecker.check(table)
        assertTrue(violations.any { v -> parts.all { it in v } }, "no violation names ${parts.toList()}: $violations")
    }

    @Test
    fun `the unmodified file reports no violation`() {
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `the file has the 19 roles and the 32 published rows`() {
        assertEquals(19, real.roles.size)
        assertEquals(RolePermissionsChecker.publishedRows.toSet(), real.permissions.map { it.row }.toSet())
        assertEquals(32, RolePermissionsChecker.publishedRows.toSet().size)
    }

    @Test
    fun `probe 1 a renamed role is reported`() {
        assertReported(withRole("tenant.admin") { it.copy(key = "tenant.admins") }, "role tenant.admins")
        assertReported(withRole("tenant.admin") { it.copy(key = "tenant.admins") }, "role tenant.admin: missing")
    }

    @Test
    fun `probe 2 a deleted role is reported`() {
        assertReported(real.copy(roles = real.roles.filter { it.key != "auditor" }), "role auditor: missing")
        assertReported(real.copy(roles = real.roles.filter { it.key != "auditor" }), "role auditor key doc.view.content form SAAS", "not in the file")
    }

    @Test
    fun `probe 3 deployment operator in SAAS is reported`() {
        assertReported(withRole("deployment.operator") { it.copy(forms = listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)) }, "role deployment.operator: forms")
    }

    @Test
    fun `probe 4 release manager in PRIVATE is reported`() {
        assertReported(withRole("release.manager") { it.copy(forms = listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)) }, "role release.manager: forms")
    }

    @Test
    fun `probe 5 the entity admin cell given to tenant admin is reported`() {
        val extra = permission(real, "entity-config.manage").cells.filter { it.role == "entity.admin" }.map { it.copy(role = "tenant.admin") }
        val table = withPermission("entity-config.manage") { it.copy(cells = it.cells + extra) }
        assertReported(table, "role tenant.admin key entity-config.manage form SAAS", "extra")
        assertReported(table, "role tenant.admin key entity-config.manage form PRIVATE", "extra")
    }

    @Test
    fun `probe 6 a removed required cell is reported`() {
        val table = withPermission("regulatory-pack.arm") { p -> p.copy(cells = p.cells.filter { it.role != "tax.approver" }) }
        assertReported(table, "role tax.approver key regulatory-pack.arm form PRIVATE", "missing")
    }

    @Test
    fun `probe 7 a deployment operator cell on arm is reported`() {
        val table = withPermission("regulatory-pack.arm") { it.copy(cells = it.cells + CellRow("deployment.operator", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED)) }
        assertReported(table, "role deployment.operator key regulatory-pack.arm form PRIVATE", "extra")
    }

    @Test
    fun `probe 8 an auditor cell on user manage is reported`() {
        val table = withPermission("user.manage") { it.copy(cells = it.cells + CellRow("auditor", DeploymentForm.SAAS, RoleAllowance.PERMITTED)) }
        assertReported(table, "role auditor key user.manage form SAAS", "WRITE")
    }

    @Test
    fun `probe 9 a repeated key is reported`() {
        assertReported(real.copy(permissions = real.permissions + permission(real, "user.manage")), "key user.manage: repeated")
    }

    @Test
    fun `probe 10 a key with a capital letter is reported`() {
        assertReported(withPermission("user.manage") { it.copy(key = "User.manage") }, "key User.manage: does not match")
    }

    @Test
    fun `probe 11 a look-alike row label is reported`() {
        val table = withPermission("user.manage") { it.copy(row = it.row + "s") }
        assertReported(table, "key user.manage", "Manage users and roless", "not one of the 32")
        assertReported(table, "row 'Manage users and roles': no key")
    }

    @Test
    fun `probe 12 a cell listed twice is reported`() {
        val twice = CellRow("tenant.admin", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED)
        val table = withPermission("user.manage") { it.copy(cells = it.cells + twice) }
        assertReported(table, "role tenant.admin key user.manage form PRIVATE", "more than once")
    }

    @Test
    fun `a cell in a form the role does not have is reported`() {
        val table = withPermission("submission.view-status") { it.copy(cells = it.cells + CellRow("deployment.operator", DeploymentForm.SAAS, RoleAllowance.PERMITTED)) }
        assertReported(table, "role deployment.operator key submission.view-status form SAAS", "does not have that form")
    }

    @Test
    fun `a changed allowance on a required cell is reported`() {
        val table = withPermission("doc.view.content") { p -> p.copy(cells = p.cells.map { if (it.role == "agent.operator") it.copy(allowance = RoleAllowance.PERMITTED) else it }) }
        assertReported(table, "role agent.operator key doc.view.content form SAAS", "required WITHIN_SCOPE")
    }

    @Test
    fun `a removed required key is reported and look-alike keys do not satisfy it`() {
        assertReported(real.copy(permissions = real.permissions.filter { it.key != "user.manage" }), "key user.manage: missing")
        assertReported(withPermission("user.manage") { it.copy(key = "user.manage2") }, "key user.manage: missing")
    }

    @Test
    fun `a changed kind on a required key is reported`() {
        assertReported(withPermission("user.manage") { it.copy(kind = PermissionKind.READ) }, "key user.manage: kind READ")
    }

    @Test
    fun `a role listed twice and a role outside section 22 2 are reported`() {
        assertReported(real.copy(roles = real.roles + real.roles.first()), "role tenant.admin: listed more than once")
        assertReported(real.copy(roles = real.roles + RoleRow("x.client", "Customer", listOf(DeploymentForm.SAAS))), "role x.client: not one of the 19")
    }

    @Test
    fun `a form listed twice on a role is reported`() {
        assertReported(withRole("deployment.operator") { it.copy(forms = listOf(DeploymentForm.PRIVATE, DeploymentForm.PRIVATE)) }, "role deployment.operator: forms")
    }
}
