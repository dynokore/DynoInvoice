package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.CONSOLE_SESSION
import com.dynokore.gst.consoleBff.application.session.SessionRow
import com.dynokore.gst.consoleBff.application.session.SessionStore
import com.dynokore.gst.consoleBff.domain.session.SessionId
import com.dynokore.gst.consoleBff.domain.session.SessionIdHash
import com.dynokore.gst.consoleBff.domain.session.SessionIdParse
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** The values of a new session, returned once: only their SHA-256 hashes are stored. */
class CreatedSession(
    val cookieValue: String,
    val csrfToken: String,
) {
    override fun toString(): String = "CreatedSession(redacted)"
}

/**
 * The session table `console_bff.console_session` through the jOOQ DSL and the generated table class (TPL-11).
 *
 * Exception to the rule that an adapter that derives its tenant from a request never receives the platform binding
 * (`PlatformTenantBinding`): the session lookup runs before any tenant is known, because the tenant is read from the
 * row. The table is platform-scoped (`bound_tenant_id`, no `tenant_id`), and every method runs all of its statements
 * inside one `bindPlatform(ACTOR) { inTransaction { ... } }` call. The binding never escapes: `find` returns the
 * row's tenant as data and the caller binds it through `TenantContext.bind`. This is the owner's widening of
 * INV-Q-2927 D to this one request-path adapter and to [SessionSweeper] only (owner answer of 2026-11-11 to the
 * answered design question of INV-T-14458, option A; best practice OWASP ASVS V3, NIST SP 800-53 AC-6).
 */
class JooqSessionStore(
    private val dsl: DSLContext,
    private val binding: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val random: SecureRandom = SecureRandom(),
) : SessionStore {
    fun create(
        tenantId: String,
        principalId: String,
        authorityVersion: Long,
        now: Instant,
    ): CreatedSession {
        val cookieValue = token()
        val csrfToken = token()
        val at = now.atOffset(ZoneOffset.UTC)
        platform {
            dsl
                .insertInto(CONSOLE_SESSION)
                .set(CONSOLE_SESSION.SESSION_ID_HASH, hashOf(cookieValue))
                .set(CONSOLE_SESSION.BOUND_TENANT_ID, UUID.fromString(tenantId))
                .set(CONSOLE_SESSION.PRINCIPAL_ID, principalId)
                .set(CONSOLE_SESSION.OPENED_AT, at)
                .set(CONSOLE_SESSION.LAST_SEEN_AT, at)
                .set(CONSOLE_SESSION.AUTHORITY_VERSION, authorityVersion)
                .set(CONSOLE_SESSION.CSRF_TOKEN_HASH, sha256(csrfToken))
                .execute()
        }
        return CreatedSession(cookieValue, csrfToken)
    }

    override fun find(hash: String): SessionRow? =
        platform {
            dsl
                .selectFrom(CONSOLE_SESSION)
                .where(CONSOLE_SESSION.SESSION_ID_HASH.eq(hash))
                .fetchOne()
        }?.let {
            SessionRow(
                hash = checkNotNull(it.sessionIdHash),
                principalId = checkNotNull(it.principalId),
                tenantId = checkNotNull(it.boundTenantId).toString(),
                authorityVersionAtOpen = checkNotNull(it.authorityVersion),
                openedAt = checkNotNull(it.openedAt).toInstant(),
                lastSeenAt = checkNotNull(it.lastSeenAt).toInstant(),
            )
        }

    /** Sets `last_seen_at` in one update; the number of rows changed is returned (0 for an unknown hash). */
    fun touchRows(
        hash: String,
        now: Instant,
    ): Int =
        platform {
            dsl
                .update(CONSOLE_SESSION)
                .set(CONSOLE_SESSION.LAST_SEEN_AT, now.atOffset(ZoneOffset.UTC))
                .where(CONSOLE_SESSION.SESSION_ID_HASH.eq(hash))
                .execute()
        }

    override fun touch(
        hash: String,
        now: Instant,
    ) {
        touchRows(hash, now)
    }

    override fun delete(hash: String) {
        platform { dsl.deleteFrom(CONSOLE_SESSION).where(CONSOLE_SESSION.SESSION_ID_HASH.eq(hash)).execute() }
    }

    /** Removes in one statement every row idle for [idle] or open for [absolute] at [now] (the `>=` bound). */
    fun deleteExpired(
        now: Instant,
        idle: Duration,
        absolute: Duration,
    ): Int = platform { deleteExpiredBound(now, idle, absolute) }

    /** The delete without its own binding or transaction: the sweeper holds the one bind and transaction. */
    internal fun deleteExpiredBound(
        now: Instant,
        idle: Duration,
        absolute: Duration,
    ): Int =
        dsl
            .deleteFrom(CONSOLE_SESSION)
            .where(
                CONSOLE_SESSION.LAST_SEEN_AT
                    .le(now.minus(idle).atOffset(ZoneOffset.UTC))
                    .or(CONSOLE_SESSION.OPENED_AT.le(now.minus(absolute).atOffset(ZoneOffset.UTC))),
            ).execute()

    private fun <T> platform(block: () -> T): T = binding.bindPlatform(ACTOR) { transactions.inTransaction(block) }

    private fun token(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashOf(cookieValue: String): String =
        when (val parsed = SessionId.parse(cookieValue)) {
            is SessionIdParse.Parsed -> SessionIdHash.of(parsed.id)
            SessionIdParse.Malformed -> error("generated cookie value is malformed")
        }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    companion object {
        /** The fixed workload actor of the platform binding of this store and of the sweeper. */
        const val ACTOR = "svc:console-bff/session-store"
        private const val TOKEN_BYTES = 32

        /**
         * The platform binding of the service's `TenantContext` bean (service-runtime's `TenantContextImpl` implements
         * both interfaces); the composition root passes the bean without naming the binding type.
         */
        fun bindingOf(tenants: TenantContext): PlatformTenantBinding =
            requireNotNull(tenants as? PlatformTenantBinding) {
                "the TenantContext bean ${tenants.javaClass.name} is not a PlatformTenantBinding"
            }

        /** The store over the service's `DSLContext`, `TenantContext` bean and `TransactionRunner`. */
        fun of(
            dsl: DSLContext,
            tenants: TenantContext,
            transactions: TransactionRunner,
        ): JooqSessionStore = JooqSessionStore(dsl, bindingOf(tenants), transactions)
    }
}
