package com.dynokore.gst.consoleBff.application.session

import java.time.Instant

/** What access-svc says the operator may see: `MASKED` unless an approved elevation exists (INV-REQ-862 b1-b3). */
enum class ContentMode { MASKED, ELEVATED }

/**
 * The operator session held in the row of a console session (INV-AC-23363). [token] is a bearer credential: it is
 * never logged and [toString] redacts it.
 */
data class OperatorSession(
    val tenantId: String,
    val token: String,
    val expiresAt: Instant,
    val contentMode: ContentMode,
) {
    override fun toString(): String = "OperatorSession(tenantId=$tenantId, expiresAt=$expiresAt, token=<redacted>)"
}

/**
 * A stored session row. It holds the hash of the cookie value, never the value. [operator] is present only while
 * its `expiresAt` is after the read time.
 */
data class SessionRow(
    val hash: String,
    val principalId: String,
    val tenantId: String,
    val authorityVersionAtOpen: Long,
    val openedAt: Instant,
    val lastSeenAt: Instant,
    val operator: OperatorSession? = null,
)

interface SessionStore {
    fun find(hash: String): SessionRow?

    fun touch(
        hash: String,
        now: Instant,
    )

    fun delete(hash: String)

    /**
     * Sets the operator session of the console session [hash], replacing an earlier one, in one statement. Returns
     * false when no such console session exists.
     */
    fun attachOperatorSession(
        hash: String,
        operator: OperatorSession,
    ): Boolean
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
