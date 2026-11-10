package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.CREDENTIAL_EXPIRY
import com.dynokore.gst.masterdata.application.connection.CredentialExpiryStore
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * TPL-11. The credential expiry of the bound tenant: one row per connection, replaced by a later record and removed
 * by a null expiry (INV-REQ-837). The tenant column is added by the tenancy-jooq listeners; row level security is
 * forced behind them. The row holds the secret-store reference only, never the secret.
 */
class JooqCredentialExpiryStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : CredentialExpiryStore {
    override fun record(
        connectionRef: String,
        credentialRef: String,
        expiresAt: Instant?,
    ) {
        if (expiresAt == null) {
            dsl
                .deleteFrom(CREDENTIAL_EXPIRY)
                .where(CREDENTIAL_EXPIRY.CONNECTION_REF.eq(connectionRef))
                .execute()
            return
        }
        val at = expiresAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(CREDENTIAL_EXPIRY)
            .set(CREDENTIAL_EXPIRY.ID, ids.next(clock.instant()))
            .set(CREDENTIAL_EXPIRY.CONNECTION_REF, connectionRef)
            .set(CREDENTIAL_EXPIRY.CREDENTIAL_REF, credentialRef)
            .set(CREDENTIAL_EXPIRY.EXPIRES_AT, at)
            .onConflict(CREDENTIAL_EXPIRY.TENANT_ID, CREDENTIAL_EXPIRY.CONNECTION_REF)
            .doUpdate()
            .set(CREDENTIAL_EXPIRY.CREDENTIAL_REF, credentialRef)
            .set(CREDENTIAL_EXPIRY.EXPIRES_AT, at)
            .execute()
    }
}
