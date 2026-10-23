package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.VERIFICATION_KEY
import com.dynokore.gst.access.application.token.VerificationKeyRegistry
import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.ZoneOffset

/**
 * INV-Q-8785 A. The verification keys are platform data (rows of the reserved platform tenant under forced row-level
 * security, unchanged), so the row is written in a transaction of its own bound to the platform tenant. The first use
 * of a `kid` inserts its row; a concurrent first use is a no-op for the loser, and both read the row that won.
 */
class JooqVerificationKeyRegistry(
    private val dsl: DSLContext,
    private val tenants: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val actor: String,
    private val ids: UuidV7 = UuidV7(),
) : VerificationKeyRegistry {
    override fun register(candidate: VerificationKey): VerificationKey =
        tenants.bindPlatform(actor) {
            tx.inTransaction {
                dsl
                    .insertInto(VERIFICATION_KEY)
                    .set(VERIFICATION_KEY.ID, ids.next(candidate.validFrom))
                    .set(VERIFICATION_KEY.KID, candidate.kid)
                    .set(VERIFICATION_KEY.ALG, candidate.alg)
                    .set(VERIFICATION_KEY.PUBLIC_KEY, candidate.publicKey)
                    .set(VERIFICATION_KEY.VALID_FROM, candidate.validFrom.atOffset(ZoneOffset.UTC))
                    .set(VERIFICATION_KEY.VALID_UNTIL, candidate.validUntil.atOffset(ZoneOffset.UTC))
                    .onConflictDoNothing()
                    .execute()
                val row =
                    checkNotNull(
                        dsl.selectFrom(VERIFICATION_KEY).where(VERIFICATION_KEY.KID.eq(candidate.kid)).fetchOne(),
                    ) { "the verification key row is missing after its insert" }
                VerificationKey(
                    row.kid.column("kid"),
                    row.alg.column("alg"),
                    row.publicKey.column("publicKey"),
                    row.validFrom.column("validFrom").toInstant(),
                    row.validUntil.column("validUntil").toInstant(),
                )
            }
        }
}
