package com.dynokore.gst.consoleBff.application.session

import java.time.Instant

/** A stored session row. It holds the hash of the cookie value, never the value. */
data class SessionRow(
    val hash: String,
    val principalId: String,
    val tenantId: String,
    val authorityVersionAtOpen: Long,
    val openedAt: Instant,
    val lastSeenAt: Instant,
)

interface SessionStore {
    fun find(hash: String): SessionRow?

    fun touch(
        hash: String,
        now: Instant,
    )

    fun delete(hash: String)
}

enum class Standing { ACTIVE, REVOKED, UNKNOWN }

/** The live answer of the authority replica (INV-API-4001, INV-REQ-1214). */
interface PrincipalStanding {
    fun of(
        tenantId: String,
        principalId: String,
        authorityVersionAtOpen: Long,
    ): Standing
}
