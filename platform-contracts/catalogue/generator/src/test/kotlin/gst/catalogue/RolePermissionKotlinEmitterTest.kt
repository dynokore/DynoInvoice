package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-T-10641: the emitted `OperationRoles` literal is built from exactly the cells of the table, deterministically. */
class RolePermissionKotlinEmitterTest {
    private val table =
        RolePermissionTable(
            roles =
                listOf(
                    RoleRow("both.role", "x", listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)),
                    RoleRow("saas.only", "x", listOf(DeploymentForm.SAAS)),
                    RoleRow("private.only", "x", listOf(DeploymentForm.PRIVATE)),
                ),
            permissions =
                listOf(
                    PermissionRow(
                        "k.one",
                        PermissionKind.READ,
                        "r1",
                        listOf(
                            CellRow("both.role", DeploymentForm.SAAS, RoleAllowance.WITHIN_SCOPE),
                            CellRow("saas.only", DeploymentForm.SAAS, RoleAllowance.PERMITTED),
                            CellRow("both.role", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED),
                        ),
                    ),
                    PermissionRow(
                        "k.two",
                        PermissionKind.WRITE,
                        "r2",
                        listOf(CellRow("saas.only", DeploymentForm.SAAS, RoleAllowance.PERMITTED)),
                    ),
                ),
        )

    private fun emitted() = RolePermissionKotlinEmitter.emit("p", table)

    /** The `OperationRoles` section as (form, key, role, allowance) rows, parsed from the text. */
    private fun operationRows(): List<List<String>> {
        val text = emitted()
        val section = text.substring(text.indexOf("object OperationRoles"))
        val rows = mutableListOf<List<String>>()
        var form = ""
        var key = ""
        for (line in section.lines().map { it.trim() }) {
            Regex("""DeploymentForm\.(\w+) to""").matchEntire(line)?.let { form = it.groupValues[1]; key = "" }
            Regex(""""([^"]+)" to""").matchEntire(line)?.let { key = it.groupValues[1] }
            Regex(""""([^"]+)" to RoleAllowance\.(\w+),""").matchEntire(line)?.let {
                rows += listOf(form, key, it.groupValues[1], it.groupValues[2])
            }
        }
        return rows
    }

    @Test
    fun `the OperationRoles literal has rows for exactly the cells of the table`() {
        val expected =
            DeploymentForm.entries.flatMap { f ->
                table.permissions.flatMap { p -> p.cells.filter { it.form == f }.map { listOf(f.name, p.key, it.role, it.allowance.name) } }
            }
        // List equality: the rows come in the order of the table (forms, then keys, then cells), not just as the same set.
        assertEquals(expected, operationRows())
    }

    @Test
    fun `a role that exists only in SAAS does not appear in the PRIVATE rows`() {
        val rows = operationRows()
        assertTrue(rows.any { it[0] == "SAAS" && it[2] == "saas.only" })
        assertFalse(rows.any { it[0] == "PRIVATE" && it[2] == "saas.only" })
    }

    @Test
    fun `two emits of the same table are equal as strings`() {
        assertEquals(emitted(), emitted())
    }
}
