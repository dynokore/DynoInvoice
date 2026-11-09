package com.dynokore.gst.tenancy.domain

/**
 * The role names the election tests grant on replica fixtures (INV-REQ-323, INV-REQ-505, INV-Q-9704 A). They live in
 * test code only: no production code of the service compares a role name (INV-Q-10107 C, INV-T-8924).
 */
object ElectionRoles {
    const val ENTITY_ADMIN = "entity.admin"
    const val TAX_APPROVER = "tax.approver"
}
