package com.dynokore.gst.tenancy.application.installation

/**
 * Decides whether a person holds a permission key at INSTALLATION scope (INV-Q-10027): only a role grant at scope
 * INSTALLATION held under the platform tenant grants it. The tenant bound by the request, if any, is never read.
 */
fun interface InstallationAuthority {
    fun holds(
        person: String,
        permission: String,
    ): Boolean
}
