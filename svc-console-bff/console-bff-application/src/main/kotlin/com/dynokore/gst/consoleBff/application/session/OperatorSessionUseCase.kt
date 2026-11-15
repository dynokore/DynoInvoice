package com.dynokore.gst.consoleBff.application.session

import java.time.Clock
import java.time.Instant
import java.util.UUID

/** The answer of access-svc's `openOperatorSession`; [Opened] holds the bearer value, so its [toString] redacts it. */
sealed interface AccessAnswer {
    data class Opened(
        val bearer: String,
        val expiresAt: Instant,
        val contentMode: ContentMode,
    ) : AccessAnswer {
        override fun toString(): String = "Opened(expiresAt=$expiresAt, contentMode=$contentMode, bearer=<redacted>)"
    }

    /** A refusal of access-svc (400, 403, 404 ...), by HTTP status only. */
    data class Refused(
        val status: Int,
    ) : AccessAnswer

    /** No usable answer (network, timeout, 5xx, unreadable body). */
    data object Failed : AccessAnswer
}

/** The port of access-svc's `openOperatorSession`. The end user's token of the request is set by the adapter. */
fun interface AccessOperatorSessions {
    fun open(
        tenantRef: String,
        caseId: String,
    ): AccessAnswer
}

/** What the use case returns to its caller: never the token (INV-AC-23363). */
sealed interface OpenOperatorSessionResult {
    data class Opened(
        val expiresAt: Instant,
        val contentMode: ContentMode,
    ) : OpenOperatorSessionResult

    data object InvalidCaseReference : OpenOperatorSessionResult

    data object InvalidTenant : OpenOperatorSessionResult

    data object NoConsoleSession : OpenOperatorSessionResult

    data class Refused(
        val status: Int,
    ) : OpenOperatorSessionResult

    data object Unavailable : OpenOperatorSessionResult
}

/**
 * Opens a tenant-scoped operator session for a resolved console session (owner ruling INV-Q-4119, INV-AC-23363) and
 * reads it back for the operator calls of that session. The case reference is checked before any call; a refused or
 * failed answer stores nothing and leaves an earlier operator session as it was.
 */
class OperatorSessionUseCase(
    private val store: SessionStore,
    private val access: AccessOperatorSessions,
    private val clock: Clock,
) {
    fun open(
        sessionHash: String,
        tenantRef: String,
        caseId: String?,
    ): OpenOperatorSessionResult =
        when {
            !CaseReference.isValid(caseId) -> OpenOperatorSessionResult.InvalidCaseReference
            !isUuid(tenantRef) -> OpenOperatorSessionResult.InvalidTenant
            store.find(sessionHash) == null -> OpenOperatorSessionResult.NoConsoleSession
            else -> call(sessionHash, tenantRef.lowercase(), checkNotNull(caseId))
        }

    private fun call(
        sessionHash: String,
        tenant: String,
        caseId: String,
    ): OpenOperatorSessionResult =
        when (val answer = access.open(tenant, caseId)) {
            is AccessAnswer.Refused -> OpenOperatorSessionResult.Refused(answer.status)
            AccessAnswer.Failed -> OpenOperatorSessionResult.Unavailable
            is AccessAnswer.Opened -> attach(sessionHash, tenant, answer)
        }

    private fun attach(
        sessionHash: String,
        tenant: String,
        answer: AccessAnswer.Opened,
    ): OpenOperatorSessionResult {
        val operator = OperatorSession(tenant, answer.bearer, answer.expiresAt, answer.contentMode)
        return if (store.attachOperatorSession(sessionHash, operator)) {
            OpenOperatorSessionResult.Opened(answer.expiresAt, answer.contentMode)
        } else {
            OpenOperatorSessionResult.NoConsoleSession
        }
    }

    /**
     * The stored operator session of [sessionHash] for the operator calls (the caller binds the tenant with
     * `TenantContext.bind`), or null with no console session, no operator session, or one at or past its expiry.
     */
    fun current(sessionHash: String): OperatorSession? =
        store.find(sessionHash)?.operator?.takeIf { it.expiresAt.isAfter(clock.instant()) }

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }.getOrNull()?.toString() == value.lowercase()
}
