package com.dynokore.gst.consoleBff.adapters.authz

import com.dynokore.gst.consoleBff.application.authz.ReadPermissions

/**
 * The permission-key source of the read authorisation filter: one table of the reads console-bff serves, each with the
 * `x-operation.permission` of its operation in the console-bff contract (the key the generated operation-to-roles map
 * is asked about). No role name is held (INV-Q-10027). Method and path template are compared by equality only; any
 * other route is unmapped (null), so the filter refuses it (OWASP ASVS V4.1.1, deny by default).
 */
class CatalogueReadPermissions : ReadPermissions {
    /** One served read: the route, the contract operation id and its permission key. */
    data class Entry(
        val method: String,
        val pathTemplate: String,
        val operationId: String,
        val permissionKey: String,
        /** The query parameter naming the entity the read is about; absent when the read names no entity. */
        val entityParameter: String? = null,
    )

    override fun keyFor(
        method: String,
        pathTemplate: String,
    ): String? = entryOf(method, pathTemplate)?.permissionKey

    override fun entityParameterFor(
        method: String,
        pathTemplate: String,
    ): String? = entryOf(method, pathTemplate)?.entityParameter

    private fun entryOf(
        method: String,
        pathTemplate: String,
    ): Entry? = ENTRIES.firstOrNull { it.method == method && it.pathTemplate == pathTemplate }

    companion object {
        val ENTRIES: List<Entry> =
            listOf(
                Entry("GET", "/v1/exceptions", "getExceptions", "exception.view"),
            )
    }
}
