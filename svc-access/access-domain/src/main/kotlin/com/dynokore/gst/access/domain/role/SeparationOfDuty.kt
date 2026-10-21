package com.dynokore.gst.access.domain.role

/**
 * Nobody holds `security.officer` and `tenant.admin` in the same tenant without the recorded sole-operator
 * acknowledgement (INV-REQ-891, INV-REQ-890). Assigning the second of the two is the refused step.
 */
object SeparationOfDuty {
    private val CONFLICTING = setOf(Role.SECURITY_OFFICER, Role.TENANT_ADMIN)

    /** Whether [adding] to a person who already holds [held] completes the conflicting pair. */
    fun completesConflict(
        held: Set<Role>,
        adding: Role,
    ): Boolean = adding in CONFLICTING && (CONFLICTING - adding).any { it in held }
}
