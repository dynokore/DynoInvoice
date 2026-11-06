package com.dynokore.platform.productruntime.catalogue

import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.gst.catalogue.DeploymentForm as GstForm

/**
 * service-runtime's [OperationRoleMap] over the generated gst-catalogue `OperationRoles` (INV-Q-8972 A, INV-T-10641)
 * for the deployment form of the installation: the roles the permission matrix lists for a permission key, each with
 * the allowance of its cell (PERMITTED or WITHIN_SCOPE, mapped by name). A key the catalogue does not hold, a role
 * name or a key with a stray character is unknown and gives the empty map (fail closed). This is the only place the
 * role vocabulary meets the replica (ARC-16).
 */
class GstOperationRoleMap(
    private val form: GstForm,
) : OperationRoleMap {
    override fun rolesFor(permissionKey: String): Map<String, CellAllowance> =
        OperationRoles.rolesFor(form, permissionKey).mapValues { CellAllowance.valueOf(it.value.name) }
}
