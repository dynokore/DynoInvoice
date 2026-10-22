package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVOCATION_ENTRY
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVOCATION_HEAD
import com.dynokore.gst.access.application.principal.RevocationListWriter
import com.dynokore.gst.access.domain.revocation.RevocationEntry
import com.dynokore.gst.access.domain.revocation.RevocationKind
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * INV-Q-8785 A. The revocation list is platform data (rows of the reserved platform tenant under forced row-level
 * security, whose migration is unchanged), so the entry is written in a transaction of its own bound to the platform
 * tenant (`PlatformTenantBinding.bindPlatform`: `TenantContext.bind` refuses the platform tenant, INV-Q-2927). The
 * head row is locked first, which serialises writers; the version is head + 1; one PRINCIPAL entry is inserted
 * unless an entry of the same principal is still inside the live window (then nothing changes, the head included).
 */
class JooqRevocationListWriter(
    private val dsl: DSLContext,
    private val tenants: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val actor: String,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : RevocationListWriter {
    override fun recordPrincipalRevoked(principalRef: String) {
        tenants.bindPlatform(actor) {
            tx.inTransaction {
                val now = clock.instant()
                val head = lockedHead()
                if (!hasLiveEntry(principalRef, now)) {
                    val version = head + 1
                    dsl
                        .insertInto(REVOCATION_ENTRY)
                        .set(REVOCATION_ENTRY.ID, ids.next(now))
                        .set(REVOCATION_ENTRY.VERSION, version)
                        .set(REVOCATION_ENTRY.KIND, RevocationKind.PRINCIPAL.name)
                        .set(REVOCATION_ENTRY.PRINCIPAL_REF, principalRef)
                        .set(REVOCATION_ENTRY.REVOKED_AT, now.atOffset(ZoneOffset.UTC))
                        .execute()
                    dsl.update(REVOCATION_HEAD).set(REVOCATION_HEAD.VERSION, version).execute()
                }
            }
        }
    }

    /** The head version under a row lock; the row exists from the first revocation on (the insert is a no-op later). */
    private fun lockedHead(): Long {
        dsl
            .insertInto(REVOCATION_HEAD)
            .set(REVOCATION_HEAD.VERSION, 0L)
            .onConflictDoNothing()
            .execute()
        val head =
            dsl
                .select(REVOCATION_HEAD.VERSION)
                .from(REVOCATION_HEAD)
                .forUpdate()
                .fetchOne(REVOCATION_HEAD.VERSION)
        return checkNotNull(head) { "the revocation head row is missing" }
    }

    private fun hasLiveEntry(
        principalRef: String,
        now: Instant,
    ): Boolean {
        val liveSince = now.minus(RevocationEntry.ACCESS_TOKEN_LIFETIME).atOffset(ZoneOffset.UTC)
        return dsl.fetchExists(
            dsl
                .selectFrom(REVOCATION_ENTRY)
                .where(REVOCATION_ENTRY.PRINCIPAL_REF.eq(principalRef), REVOCATION_ENTRY.REVOKED_AT.gt(liveSince)),
        )
    }
}
