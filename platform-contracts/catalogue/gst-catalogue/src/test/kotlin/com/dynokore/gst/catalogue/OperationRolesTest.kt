package com.dynokore.gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** INV-AC-10640: the operation-to-roles map, asserted through the published API only. */
class OperationRolesTest {
    private val forms = listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)

    @Test
    fun `INV-AC-10640 the map equals the cells of RolePermissions in every form`() {
        for (form in forms) {
            val cells = RolePermissions.cells(form)
            val allKeys = cells.values.flatMap { it.keys }.toSet()
            val personKeys = OperationRoles.keys(form).filterNot { it.startsWith("machine.") }.toSet()
            assertEquals(allKeys, personKeys, "keys $form")
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

    // INV-T-13568, INV-AC-13562: the machine section, read back against the tsv itself.
    private val tsvRows =
        File("../../access/schemas/machine-principal-permissions.tsv")
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { it.split('\t') }

    @Test
    fun `INV-AC-13562 the tsv has 19 data rows and every one is a machine key with its machine role`() {
        assertEquals(19, tsvRows.size)
        for (form in forms) {
            for ((type, permission) in tsvRows.map { it[0] to it[1] }) {
                val role = "machine." + type.lowercase().replace('_', '-')
                val roles = OperationRoles.rolesFor(form, "machine.$permission")
                assertTrue(roles.containsKey(role), "$form $type $permission")
            }
            assertEquals(19, OperationRoles.keys(form).count { it.startsWith("machine.") }, "machine key count $form")
        }
    }

    @Test
    fun `INV-AC-13562 drain run is exactly the filing-workflow machine role`() {
        for (form in forms) {
            val expected = mapOf("machine.filing-workflow" to RoleAllowance.PERMITTED)
            assertEquals(expected, OperationRoles.rolesFor(form, "machine.drain.run"))
        }
    }

    @Test
    fun `INV-AC-13562 the machine roles are the six names`() {
        val roles =
            forms
                .flatMap { f ->
                    val machineKeys = OperationRoles.keys(f).filter { it.startsWith("machine.") }
                    machineKeys.flatMap { OperationRoles.rolesFor(f, it).keys }
                }.toSet()
        assertEquals(
            listOf("ingest", "filing-workflow", "ap-callback", "reporting", "monitoring", "certification")
                .map { "machine.$it" }
                .toSet(),
            roles,
        )
    }

    @Test
    fun `INV-AC-13562 look-alikes of the machine key are empty`() {
        for (form in forms) {
            for (key in listOf(
                "drain.run",
                "machine.drain.run ",
                "Machine.drain.run",
                "machine.machine.drain.run",
            )) {
                assertEquals(emptyMap<String, RoleAllowance>(), OperationRoles.rolesFor(form, key), "$form '$key'")
            }
            // machine.filing.confirm is a real tsv row (FILING_WORKFLOW): it must name no person role.
            assertEquals(
                mapOf("machine.filing-workflow" to RoleAllowance.PERMITTED),
                OperationRoles.rolesFor(form, "machine.filing.confirm"),
                "$form machine.filing.confirm",
            )
            assertEquals(
                setOf("tax.approver", "agent.operator"),
                OperationRoles.rolesFor(form, "filing.confirm").keys,
                "person key filing.confirm $form",
            )
            assertEquals(2, OperationRoles.rolesFor(form, "filing.confirm").size, "person key filing.confirm $form")
        }
    }
}
