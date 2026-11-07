package com.dynokore.gst.controlPlane.application.commercial

/** The permission keys of the commercial reads; each is a row of the catalogue's `OperationRoles` (INV-Q-16272 A). */
object CommercialPermission {
    /** getLicence. */
    const val LICENCE_VIEW = "licence.view"

    /** getTenantProvisioningRequest. */
    const val TENANT_PROVISIONING_REQUEST_VIEW = "tenant-provisioning-request.view"
}
