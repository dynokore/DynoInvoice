package gst.catalogue

import org.yaml.snakeyaml.Yaml
import java.io.File

/** What a permission cell allows: the published Y or S of INV-NOTE-3 section 22.3 (INV-T-7987). */
enum class RoleAllowance {
    PERMITTED,
    WITHIN_SCOPE,
}

/** Whether a permission reads or changes state; an `auditor` may hold READ permissions only (INV-REQ-863, FR-P.5). */
enum class PermissionKind {
    READ,
    WRITE,
}

/** One role of INV-NOTE-3 section 22.2: [belongsTo] in the words of that table, [forms] the deployment forms it exists in. */
data class RoleRow(
    val key: String,
    val belongsTo: String,
    val forms: List<DeploymentForm>,
)

/** One listed cell; a cell that is not listed is DENIED. */
data class CellRow(
    val role: String,
    val form: DeploymentForm,
    val allowance: RoleAllowance,
)

/** One permission key: the published [row] label it transcribes and its listed [cells]. */
data class PermissionRow(
    val key: String,
    val kind: PermissionKind,
    val row: String,
    val cells: List<CellRow>,
)

data class RolePermissionTable(
    val roles: List<RoleRow>,
    val permissions: List<PermissionRow>,
)

/**
 * Parses `role-permissions.yaml` (catalogue/role-permissions/, INV-T-7987) strictly: an unknown key, a wrongly typed
 * field or an unknown enum value fails. It does not judge the content (a repeated key, a cell naming no role): that is
 * [RolePermissionsChecker]'s job, so one run reports every violation.
 */
object RolePermissionYamlParser {
    private val rootKeys = setOf("roles", "permissions")
    private val roleKeys = setOf("key", "belongs_to", "forms")
    private val permissionKeys = setOf("key", "kind", "row", "cells")
    private val cellKeys = setOf("role", "form", "allowance")

    fun parse(file: File): RolePermissionTable {
        val root = Yaml().load<Any?>(file.readText())
        val top = asMap(root, "top level", file)
        unknownKeys(top, rootKeys, "top level", file)
        val roles = asList(top["roles"], "roles", file).mapIndexed { i, r -> parseRole(asMap(r, "roles[$i]", file), "roles[$i]", file) }
        val permissions =
            asList(top["permissions"], "permissions", file).mapIndexed { i, p ->
                parsePermission(asMap(p, "permissions[$i]", file), "permissions[$i]", file)
            }
        return RolePermissionTable(roles, permissions)
    }

    private fun parseRole(
        m: Map<String, Any?>,
        where: String,
        file: File,
    ): RoleRow {
        unknownKeys(m, roleKeys, where, file)
        return RoleRow(
            key = text(m, "key", where, file),
            belongsTo = text(m, "belongs_to", where, file),
            forms = asList(m["forms"], "$where forms", file).map { enumOf<DeploymentForm>(it, "$where form", file) },
        )
    }

    private fun parsePermission(
        m: Map<String, Any?>,
        where: String,
        file: File,
    ): PermissionRow {
        unknownKeys(m, permissionKeys, where, file)
        return PermissionRow(
            key = text(m, "key", where, file),
            kind = enumOf<PermissionKind>(m["kind"], "$where kind", file),
            row = text(m, "row", where, file),
            cells =
                asList(m["cells"], "$where cells", file).mapIndexed { i, c ->
                    val cm = asMap(c, "$where cells[$i]", file)
                    unknownKeys(cm, cellKeys, "$where cells[$i]", file)
                    CellRow(
                        role = text(cm, "role", "$where cells[$i]", file),
                        form = enumOf<DeploymentForm>(cm["form"], "$where cells[$i] form", file),
                        allowance = enumOf<RoleAllowance>(cm["allowance"], "$where cells[$i] allowance", file),
                    )
                },
        )
    }

    private fun unknownKeys(
        m: Map<String, Any?>,
        allowed: Set<String>,
        where: String,
        file: File,
    ) {
        val unknown = m.keys - allowed
        if (unknown.isNotEmpty()) error("gst-catalogue: $where in $file has unknown key(s) ${unknown.joinToString()}.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(
        v: Any?,
        where: String,
        file: File,
    ): Map<String, Any?> = v as? Map<String, Any?> ?: error("gst-catalogue: $where in $file is not a mapping.")

    private fun asList(
        v: Any?,
        where: String,
        file: File,
    ): List<Any?> = v as? List<Any?> ?: error("gst-catalogue: $where in $file is not a list.")

    private fun text(
        m: Map<String, Any?>,
        name: String,
        where: String,
        file: File,
    ): String = m[name] as? String ?: error("gst-catalogue: $where in $file has no text '$name'.")

    private inline fun <reified E : Enum<E>> enumOf(
        v: Any?,
        where: String,
        file: File,
    ): E =
        enumValues<E>().find { it.name == v }
            ?: error("gst-catalogue: $where in $file is '$v', not one of ${enumValues<E>().joinToString()}.")
}
