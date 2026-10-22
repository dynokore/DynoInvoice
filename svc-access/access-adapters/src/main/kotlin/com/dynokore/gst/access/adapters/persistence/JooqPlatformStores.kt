package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVOCATION_ENTRY
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVOCATION_HEAD
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.VERIFICATION_KEY
import com.dynokore.gst.access.application.keys.EntryPosition
import com.dynokore.gst.access.application.keys.RevocationStore
import com.dynokore.gst.access.application.keys.VerificationKeyStore
import com.dynokore.gst.access.domain.revocation.RevocationEntry
import com.dynokore.gst.access.domain.revocation.RevocationKind
import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.ZoneOffset

/**
 * Runs [block] in one read transaction bound to the platform tenant, under which the gateway's token material is kept
 * (platform data, not tenant data; the rows carry the reserved platform tenant). Each call is one consistent snapshot.
 */
class PlatformReads(
    private val tenants: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val actor: String,
) {
    fun <T> read(block: () -> T): T = tenants.bindPlatform(actor) { tx.inTransaction(block) }
}

class JooqRevocationStore(
    private val dsl: DSLContext,
    private val reads: PlatformReads,
) : RevocationStore {
    override fun currentVersion(): Long =
        reads.read { dsl.select(REVOCATION_HEAD.VERSION).from(REVOCATION_HEAD).fetchOne(REVOCATION_HEAD.VERSION) ?: 0L }

    override fun live(
        afterVersion: Long,
        throughVersion: Long,
        now: Instant,
        position: EntryPosition?,
        limit: Int,
    ): List<RevocationEntry> =
        reads.read {
            val liveSince = now.minus(RevocationEntry.ACCESS_TOKEN_LIFETIME).atOffset(ZoneOffset.UTC)
            dsl
                .selectFrom(REVOCATION_ENTRY)
                .where(
                    REVOCATION_ENTRY.VERSION.gt(afterVersion),
                    REVOCATION_ENTRY.VERSION.le(throughVersion),
                    REVOCATION_ENTRY.REVOKED_AT.gt(liveSince), // expiresAt = revokedAt + 15 min is after now
                    if (position == null) {
                        DSL.noCondition()
                    } else {
                        DSL
                            .row(
                                REVOCATION_ENTRY.VERSION,
                                REVOCATION_ENTRY.ID,
                            ).gt(DSL.row(position.version, position.entryId))
                    },
                ).orderBy(REVOCATION_ENTRY.VERSION, REVOCATION_ENTRY.ID)
                .limit(limit)
                .fetch()
                .map {
                    RevocationEntry(
                        entryId = it.id.column("id"),
                        version = it.version.column("version"),
                        kind = RevocationKind.valueOf(it.kind.column("kind")),
                        principalRef = it.principalRef,
                        subject = it.subject,
                        tokenId = it.tokenId,
                        revokedAt = it.revokedAt.column("revokedAt").toInstant(),
                    )
                }
        }
}

class JooqVerificationKeyStore(
    private val dsl: DSLContext,
    private val reads: PlatformReads,
) : VerificationKeyStore {
    override fun all(): List<VerificationKey> =
        reads.read {
            dsl.selectFrom(VERIFICATION_KEY).orderBy(VERIFICATION_KEY.KID).fetch().map {
                VerificationKey(
                    it.kid.column("kid"),
                    it.alg.column("alg"),
                    it.publicKey.column("publicKey"),
                    it.validFrom.column("validFrom").toInstant(),
                    it.validUntil.column("validUntil").toInstant(),
                )
            }
        }
}
