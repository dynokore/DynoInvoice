package com.dynokore.gst.ingestion.application.permission

/** The caller of the request in hand: the actor of the verified end-user token (API-24). Refuses when there is none. */
fun interface CallerSource {
    fun actor(): String
}

/**
 * Whether [actor] holds the scoped permission [permissionKey] on [entityRef], decided per request by the authority
 * replica (INV-Q-10027). Never a role name; false whenever the answer is not a current, local grant (fail closed).
 */
fun interface EntityPermission {
    fun holds(
        actor: String,
        entityRef: String,
        permissionKey: String,
    ): Boolean
}
