package com.dynokore.gst.consoleBff.application.session

import com.dynokore.gst.consoleBff.domain.session.SessionId
import com.dynokore.gst.consoleBff.domain.session.SessionIdHash
import com.dynokore.gst.consoleBff.domain.session.SessionIdParse
import com.dynokore.gst.consoleBff.domain.session.SessionPolicy
import com.dynokore.gst.consoleBff.domain.session.SessionVerdict
import java.time.Clock
import java.time.Instant

enum class RefusalReason {
    NO_COOKIE,
    MALFORMED,
    UNKNOWN_SESSION,
    EXPIRED,
    REVOKED,
    AUTHORITY_UNAVAILABLE,
    STORE_UNAVAILABLE,
}

sealed interface SessionResolution {
    data class Resolved(
        val principalId: String,
        val tenantId: String,
    ) : SessionResolution

    data class Refused(
        val reason: RefusalReason,
    ) : SessionResolution
}

/**
 * Resolves a cookie value per request from the stored row and the live replica answer; nothing is cached.
 * Fails closed: any store or standing exception is STORE_UNAVAILABLE.
 */
class SessionResolver(
    private val policy: SessionPolicy,
    private val store: SessionStore,
    private val standing: PrincipalStanding,
    private val clock: Clock,
) {
    fun resolve(cookieValue: String?): SessionResolution =
        if (cookieValue.isNullOrBlank()) {
            refuse(RefusalReason.NO_COOKIE)
        } else {
            when (val parsed = SessionId.parse(cookieValue)) {
                SessionIdParse.Malformed -> refuse(RefusalReason.MALFORMED)
                is SessionIdParse.Parsed -> resolveHash(SessionIdHash.of(parsed.id))
            }
        }

    private fun resolveHash(hash: String): SessionResolution =
        try {
            val row = store.find(hash)
            if (row == null) refuse(RefusalReason.UNKNOWN_SESSION) else resolveRow(hash, row)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // fail closed; the cause is dropped on purpose so no store text reaches a refusal
            refuse(RefusalReason.STORE_UNAVAILABLE)
        }

    private fun resolveRow(
        hash: String,
        row: SessionRow,
    ): SessionResolution {
        val now = clock.instant()
        return when (policy.evaluate(row.openedAt, row.lastSeenAt, now)) {
            SessionVerdict.Valid -> {
                resolveStanding(hash, row, now)
            }

            SessionVerdict.IdleExpired, SessionVerdict.AbsoluteExpired -> {
                store.delete(hash)
                refuse(RefusalReason.EXPIRED)
            }

            SessionVerdict.ClockSkew -> {
                refuse(RefusalReason.EXPIRED)
            }
        }
    }

    private fun resolveStanding(
        hash: String,
        row: SessionRow,
        now: Instant,
    ): SessionResolution =
        when (standing.of(row.tenantId, row.principalId, row.authorityVersionAtOpen)) {
            Standing.REVOKED -> {
                store.delete(hash)
                refuse(RefusalReason.REVOKED)
            }

            Standing.UNKNOWN -> {
                refuse(RefusalReason.AUTHORITY_UNAVAILABLE)
            }

            Standing.ACTIVE -> {
                store.touch(hash, now)
                SessionResolution.Resolved(row.principalId, row.tenantId)
            }
        }

    private fun refuse(reason: RefusalReason) = SessionResolution.Refused(reason)
}
