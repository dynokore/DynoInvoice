package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.CREDENTIAL_EXPIRY
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.CREDENTIAL_REMINDER
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.CREDENTIAL_STATE
import com.dynokore.gst.masterdata.application.connection.CredentialExpiries
import com.dynokore.gst.masterdata.application.connection.CredentialExpiry
import com.dynokore.gst.masterdata.application.connection.CredentialState
import com.dynokore.gst.masterdata.application.connection.CredentialStateStore
import com.dynokore.gst.masterdata.application.connection.ReminderLog
import com.dynokore.gst.masterdata.application.connection.Rotated
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/**
 * TPL-11. The current and previous credential reference of each connection of the bound tenant, the overlap end, and
 * the last rotation (INV-REQ-837). Only secret-store references are held, never a secret.
 */
class JooqCredentialStateStore(
    private val dsl: DSLContext,
) : CredentialStateStore {
    override fun find(connectionRef: String): CredentialState? =
        dsl
            .selectFrom(CREDENTIAL_STATE)
            .where(CREDENTIAL_STATE.RECORD_REF.eq(connectionRef))
            .fetchOne()
            ?.let { row ->
                val current = checkNotNull(row.get(CREDENTIAL_STATE.CURRENT_REF))
                val rotatedAt = row.get(CREDENTIAL_STATE.ROTATED_AT)?.toInstant()
                val lastPrevious = row.get(CREDENTIAL_STATE.LAST_PREVIOUS_REF)
                val lastEnd = row.get(CREDENTIAL_STATE.LAST_OVERLAP_END)?.toInstant()
                CredentialState(
                    connectionRef = connectionRef,
                    currentRef = current,
                    previousRef = row.get(CREDENTIAL_STATE.PREVIOUS_REF),
                    overlapEnd = row.get(CREDENTIAL_STATE.OVERLAP_END)?.toInstant(),
                    rotatedAt = rotatedAt,
                    last =
                        if (lastPrevious != null && lastEnd != null && rotatedAt != null) {
                            Rotated(connectionRef, current, lastPrevious, lastEnd, rotatedAt)
                        } else {
                            null
                        },
                )
            }

    override fun save(state: CredentialState) {
        val overlapEnd = state.overlapEnd?.atOffset(ZoneOffset.UTC)
        val rotatedAt = state.rotatedAt?.atOffset(ZoneOffset.UTC)
        val lastEnd = state.last?.overlapEnd?.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(CREDENTIAL_STATE)
            .set(CREDENTIAL_STATE.RECORD_REF, state.connectionRef)
            .set(CREDENTIAL_STATE.CURRENT_REF, state.currentRef)
            .set(CREDENTIAL_STATE.PREVIOUS_REF, state.previousRef)
            .set(CREDENTIAL_STATE.OVERLAP_END, overlapEnd)
            .set(CREDENTIAL_STATE.ROTATED_AT, rotatedAt)
            .set(CREDENTIAL_STATE.LAST_PREVIOUS_REF, state.last?.previousRef)
            .set(CREDENTIAL_STATE.LAST_OVERLAP_END, lastEnd)
            .onConflict(CREDENTIAL_STATE.TENANT_ID, CREDENTIAL_STATE.RECORD_REF)
            .doUpdate()
            .set(CREDENTIAL_STATE.CURRENT_REF, state.currentRef)
            .set(CREDENTIAL_STATE.PREVIOUS_REF, state.previousRef)
            .set(CREDENTIAL_STATE.OVERLAP_END, overlapEnd)
            .set(CREDENTIAL_STATE.ROTATED_AT, rotatedAt)
            .set(CREDENTIAL_STATE.LAST_PREVIOUS_REF, state.last?.previousRef)
            .set(CREDENTIAL_STATE.LAST_OVERLAP_END, lastEnd)
            .execute()
    }
}

/**
 * The adapter of `CredentialExpiries`: reads `masterdata.credential_expiry`, the table INV-T-19568 creates and
 * writes. This class never writes it.
 */
class JooqCredentialExpiries(
    private val dsl: DSLContext,
) : CredentialExpiries {
    override fun all(): List<CredentialExpiry> =
        dsl
            .selectFrom(CREDENTIAL_EXPIRY)
            .orderBy(CREDENTIAL_EXPIRY.RECORD_REF.asc())
            .fetch()
            .map {
                CredentialExpiry(
                    checkNotNull(it.get(CREDENTIAL_EXPIRY.RECORD_REF)),
                    checkNotNull(it.get(CREDENTIAL_EXPIRY.CREDENTIAL_REF)),
                    checkNotNull(it.get(CREDENTIAL_EXPIRY.EXPIRES_AT)).toInstant(),
                )
            }

    /** The expiry of one connection, or null when it has none. */
    fun of(connectionRef: String): CredentialExpiry? = all().firstOrNull { it.connectionRef == connectionRef }
}

/** TPL-11. The reminders already requested, so that each (connection, credential, offset) is requested once. */
class JooqReminderLog(
    private val dsl: DSLContext,
    private val clock: Clock,
) : ReminderLog {
    override fun wasReminded(
        connectionRef: String,
        credentialRef: String,
        offsetDays: Int,
    ): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(CREDENTIAL_REMINDER).where(
                CREDENTIAL_REMINDER.RECORD_REF.eq(connectionRef),
                CREDENTIAL_REMINDER.CREDENTIAL_REF.eq(credentialRef),
                CREDENTIAL_REMINDER.OFFSET_DAYS.eq(offsetDays),
            ),
        )

    override fun markReminded(
        connectionRef: String,
        credentialRef: String,
        offsetDays: Int,
    ) {
        dsl
            .insertInto(CREDENTIAL_REMINDER)
            .set(CREDENTIAL_REMINDER.RECORD_REF, connectionRef)
            .set(CREDENTIAL_REMINDER.CREDENTIAL_REF, credentialRef)
            .set(CREDENTIAL_REMINDER.OFFSET_DAYS, offsetDays)
            .set(CREDENTIAL_REMINDER.REMINDED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute()
    }
}
