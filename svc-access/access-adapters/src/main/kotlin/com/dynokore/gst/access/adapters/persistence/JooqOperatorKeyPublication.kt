package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.events.OperatorSessionKeysChangedOutbox
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.VERIFICATION_KEY
import com.dynokore.gst.access.application.operatorsession.OperatorKeyPublication
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.gst.access.domain.revocation.KeyUse
import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

/**
 * INV-Q-16473 A: the verification-key rows and the outbox record of OperatorSessionKeysChanged are written in one
 * platform-bound transaction, so a key is published (committed) before its first signing. A kid already stored keeps
 * its row; a candidate whose kid is stored under another use is refused. When any row was new, the event carries every
 * key still valid, machine keys included, since a replica replaces both sets from one event.
 */
class JooqOperatorKeyPublication(
    private val dsl: DSLContext,
    private val tenants: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val outbox: OperatorSessionKeysChangedOutbox,
    private val actor: String,
    private val ids: UuidV7 = UuidV7(),
) : OperatorKeyPublication {
    override fun publish(candidates: List<VerificationKey>): List<VerificationKey> =
        tenants.bindPlatform(actor) {
            tx.inTransaction {
                val inserted =
                    candidates.sumOf { candidate ->
                        dsl
                            .insertInto(VERIFICATION_KEY)
                            .set(VERIFICATION_KEY.ID, ids.next(candidate.validFrom))
                            .set(VERIFICATION_KEY.KID, candidate.kid)
                            .set(VERIFICATION_KEY.ALG, candidate.alg)
                            .set(VERIFICATION_KEY.PUBLIC_KEY, candidate.publicKey)
                            .set(VERIFICATION_KEY.USE, candidate.use.name)
                            .set(VERIFICATION_KEY.VALID_FROM, candidate.validFrom.atOffset(ZoneOffset.UTC))
                            .set(VERIFICATION_KEY.VALID_UNTIL, candidate.validUntil.atOffset(ZoneOffset.UTC))
                            .onConflictDoNothing()
                            .execute()
                    }
                val rows = storedKeys()
                val stored =
                    candidates.map { candidate ->
                        rows.singleOrNull { it.kid == candidate.kid && it.use == candidate.use }
                            ?: run {
                                log.error("key publication refused: kid {} is stored under another use", candidate.kid)
                                throw SigningKeyUnavailable("the key ${candidate.kid} is stored under another use")
                            }
                    }
                val now = candidates.maxOf { it.validFrom }
                val valid = rows.filter { it.validUntil.isAfter(now) }
                // a new row or an expired key leaving the valid set changes what replicas must hold
                if (inserted > 0 || !lastPublishedCarries(valid)) outbox.publish(valid)
                stored
            }
        }

    private fun storedKeys(): List<VerificationKey> =
        dsl.selectFrom(VERIFICATION_KEY).orderBy(VERIFICATION_KEY.KID).fetch().map {
            VerificationKey(
                it.kid.column("kid"),
                it.alg.column("alg"),
                it.publicKey.column("publicKey"),
                it.validFrom.column("validFrom").toInstant(),
                it.validUntil.column("validUntil").toInstant(),
                KeyUse.valueOf(it.use.column("use")),
            )
        }

    private companion object {
        private val log = LoggerFactory.getLogger(JooqOperatorKeyPublication::class.java)
    }

    private fun lastPublishedCarries(valid: List<VerificationKey>): Boolean {
        val last =
            dsl
                .select(DSL.field(DSL.name("data"), SQLDataType.JSONB))
                .from(DSL.table(DSL.name("access", "outbox")))
                .where(DSL.field(DSL.name("type"), SQLDataType.CLOB).eq(OperatorSessionKeysChangedOutbox.EVENT_TYPE))
                .orderBy(DSL.field(DSL.name("seq"), SQLDataType.BIGINT).desc())
                .limit(1)
                .fetchOne(0, org.jooq.JSONB::class.java)
                ?.data()
        return last != null && outbox.carries(last, valid)
    }
}
