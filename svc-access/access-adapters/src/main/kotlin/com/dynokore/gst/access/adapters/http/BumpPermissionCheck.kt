package com.dynokore.gst.access.adapters.http

/** The caller check of `bumpAuthorityIncarnation`: the permission key, decided per request (INV-Q-10027). */
fun interface BumpPermissionCheck {
    fun mayBump(actor: String): Boolean
}
