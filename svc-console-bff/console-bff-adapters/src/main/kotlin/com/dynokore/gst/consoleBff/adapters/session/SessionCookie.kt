package com.dynokore.gst.consoleBff.adapters.session

/** The session cookie (OWASP ASVS V3.4): `__Host-` prefix, so Secure, Path=/ and no Domain are required by browsers. */
object SessionCookie {
    const val NAME = "__Host-console-session"

    /** `__Host-console-session=<value>; Path=/; Secure; HttpOnly; SameSite=Strict`; no Domain, Max-Age, Expires. */
    fun render(value: String): String = "$NAME=$value; Path=/; Secure; HttpOnly; SameSite=Strict"
}
