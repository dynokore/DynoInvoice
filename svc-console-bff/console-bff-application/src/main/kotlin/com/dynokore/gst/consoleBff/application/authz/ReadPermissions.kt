package com.dynokore.gst.consoleBff.application.authz

/**
 * The permission key of a read operation of console-bff (INV-REQ-871 behavior 1, INV-Q-10027): the key the authority
 * replica is asked about, never a role name.
 */
fun interface ReadPermissions {
    /** The permission key of the route ([method], [pathTemplate]), or null when the route is unmapped (fail closed). */
    fun keyFor(
        method: String,
        pathTemplate: String,
    ): String?

    /**
     * The name of the query parameter that carries the entity a read of the route names, or null when the read names
     * no entity (then it is decided at tenant scope). Meaningful only for a mapped route.
     */
    fun entityParameterFor(
        method: String,
        pathTemplate: String,
    ): String? = null
}
