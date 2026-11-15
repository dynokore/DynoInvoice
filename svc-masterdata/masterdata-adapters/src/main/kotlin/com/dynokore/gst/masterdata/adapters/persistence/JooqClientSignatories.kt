package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.records.ClientSignatoryRecord
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.CLIENT_SIGNATORY
import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatories
import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatory
import com.dynokore.gst.masterdata.application.clientsignatory.LinkRefusalReason
import com.dynokore.gst.masterdata.application.clientsignatory.LinkState
import com.dynokore.gst.masterdata.application.clientsignatory.LinkStatus
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/**
 * TPL-11. The client signatories of the bound tenant; the tenancy-jooq listeners add the tenant predicate to every
 * statement. A signatory reference is `SIG-` and the 32 hex digits of a UUIDv7, 36 characters (the contract allows 64).
 */
class JooqClientSignatories(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : ClientSignatories {
    override fun findByClientAndEmail(
        clientRef: String,
        email: String,
    ): ClientSignatory? =
        dsl
            .selectFrom(CLIENT_SIGNATORY)
            .where(CLIENT_SIGNATORY.CLIENT_REF.eq(clientRef), CLIENT_SIGNATORY.SIGNATORY_EMAIL.eq(email))
            .fetchOne()
            ?.let(::signatory)

    override fun findByRequestEventId(requestEventId: String): ClientSignatory? =
        dsl
            .selectFrom(CLIENT_SIGNATORY)
            .where(CLIENT_SIGNATORY.REQUEST_EVENT_ID.eq(requestEventId))
            .fetchOne()
            ?.let(::signatory)

    override fun insert(signatory: ClientSignatory) {
        dsl
            .insertInto(CLIENT_SIGNATORY)
            .set(CLIENT_SIGNATORY.SIGNATORY_REF, signatory.signatoryRef)
            .set(CLIENT_SIGNATORY.CLIENT_REF, signatory.clientRef)
            .set(CLIENT_SIGNATORY.SIGNATORY_NAME, signatory.name)
            .set(CLIENT_SIGNATORY.SIGNATORY_EMAIL, signatory.email)
            .set(CLIENT_SIGNATORY.SIGNATORY_MOBILE, signatory.mobile)
            .set(CLIENT_SIGNATORY.CONSENT_REF, signatory.consentRef)
            .set(CLIENT_SIGNATORY.LINK_STATE, signatory.linkStatus.state.name)
            .set(CLIENT_SIGNATORY.LINK_REASON_CODE, signatory.linkStatus.reasonCode?.name)
            .set(CLIENT_SIGNATORY.REQUEST_EVENT_ID, signatory.requestEventId)
            .set(CLIENT_SIGNATORY.RECORDED_AT, signatory.recordedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    override fun saveRequestEventId(
        signatoryRef: String,
        requestEventId: String,
    ) {
        dsl
            .update(CLIENT_SIGNATORY)
            .set(CLIENT_SIGNATORY.REQUEST_EVENT_ID, requestEventId)
            .where(CLIENT_SIGNATORY.SIGNATORY_REF.eq(signatoryRef))
            .execute()
    }

    override fun saveLinkStatus(
        signatoryRef: String,
        status: LinkStatus,
    ) {
        dsl
            .update(CLIENT_SIGNATORY)
            .set(CLIENT_SIGNATORY.LINK_STATE, status.state.name)
            .set(CLIENT_SIGNATORY.LINK_REASON_CODE, status.reasonCode?.name)
            .where(CLIENT_SIGNATORY.SIGNATORY_REF.eq(signatoryRef))
            .execute()
    }

    override fun nextRef(): String = "SIG-" + ids.next(clock.instant()).toString().replace("-", "")

    private fun signatory(record: ClientSignatoryRecord) =
        ClientSignatory(
            signatoryRef = record.signatoryRef.column("signatory_ref"),
            clientRef = record.clientRef.column("client_ref"),
            name = record.signatoryName.column("signatory_name"),
            email = record.signatoryEmail.column("signatory_email"),
            mobile = record.signatoryMobile,
            consentRef = record.consentRef.column("consent_ref"),
            linkStatus =
                LinkStatus(
                    LinkState.valueOf(record.linkState.column("link_state")),
                    record.linkReasonCode?.let { LinkRefusalReason.valueOf(it) },
                ),
            requestEventId = record.requestEventId,
            recordedAt = record.recordedAt.column("recorded_at").toInstant(),
        )
}
