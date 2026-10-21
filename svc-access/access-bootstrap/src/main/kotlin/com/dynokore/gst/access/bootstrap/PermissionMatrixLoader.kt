package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.catalogue.PermissionKind as CataloguePermissionKind

/**
 * Builds the permission matrix and the role catalogue of one deployment form from the role-by-permission table of the
 * gst-catalogue (INV-T-7987, INV-Q-7979 A). It fails closed: a role key that [Role.ofWire] does not know, a permission
 * key without a kind, or a matrix the domain refuses stops the start-up with the key named, so a table that moved
 * ahead of this service is never half-read.
 */
object PermissionMatrixLoader {
    fun load(
        form: DeploymentForm,
        table: CatalogueTable = CatalogueTable.PUBLISHED,
    ): PermissionMatrix {
        val cells = table.cells(form)
        val rows =
            cells.entries.associate { (roleKey, permissions) ->
                roleOf(roleKey, form) to
                    permissions.entries.associate { (key, allowance) -> permissionOf(key) to allowanceOf(allowance) }
            }
        val definitions =
            cells.values
                .flatMap { it.keys }
                .toSet()
                .map { key ->
                    val kind =
                        checkNotNull(
                            table.kind(key),
                        ) { "the catalogue lists permission $key (form $form) with no kind" }
                    PermissionDefinition(permissionOf(key), kindOf(kind))
                }
        return PermissionMatrix(definitions, rows)
    }

    /** The roles that exist in [form]; a role key this service does not know stops the start-up. */
    fun roleCatalogue(
        form: DeploymentForm,
        table: CatalogueTable = CatalogueTable.PUBLISHED,
    ): RoleCatalogue = RoleCatalogue(table.roles(form).map { roleOf(it, form) }.toSet())

    private fun roleOf(
        key: String,
        form: DeploymentForm,
    ): Role =
        checkNotNull(Role.ofWire(key)) { "the catalogue lists role $key (form $form) that access-svc does not know" }

    private fun permissionOf(key: String): Permission =
        runCatching {
            Permission(
                key,
            )
        }.getOrElse { error("the catalogue permission key '$key' is not usable: ${it.message}") }

    private fun allowanceOf(allowance: RoleAllowance): Allowance =
        when (allowance) {
            RoleAllowance.PERMITTED -> Allowance.PERMITTED
            RoleAllowance.WITHIN_SCOPE -> Allowance.WITHIN_SCOPE
        }

    private fun kindOf(kind: CataloguePermissionKind): PermissionKind =
        when (kind) {
            CataloguePermissionKind.READ -> PermissionKind.READ
            CataloguePermissionKind.WRITE -> PermissionKind.WRITE
        }
}

/** The three reads of the catalogue's `RolePermissions` the loader needs; a test passes its own to probe the loader. */
interface CatalogueTable {
    fun roles(form: DeploymentForm): Set<String>

    fun cells(form: DeploymentForm): Map<String, Map<String, RoleAllowance>>

    fun kind(permissionKey: String): CataloguePermissionKind?

    companion object {
        /** The table of the gst-catalogue version this build resolves. */
        val PUBLISHED =
            object : CatalogueTable {
                override fun roles(form: DeploymentForm) = RolePermissions.roles(form)

                override fun cells(form: DeploymentForm) = RolePermissions.cells(form)

                override fun kind(permissionKey: String) = RolePermissions.kind(permissionKey)
            }
    }
}
