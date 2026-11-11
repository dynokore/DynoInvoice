package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.SealedPayload.Companion.SEALED_PAYLOAD
import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.application.payload.SealedPayloadSink
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.util.UUID

/**
 * Appends the sealed content as an EVIDENCE_PAYLOAD record and links it to its version in `sealed_payload`, in the
 * caller's transaction. A repeat of [eventId] writes neither: the appender returns on an applied event and the link
 * insert conflicts.
 */
class JooqSealedPayloadSink(
    private val dsl: DSLContext,
    private val appender: RecordAppender,
) : SealedPayloadSink {
    override fun append(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
        eventId: String,
        content: ByteArray,
        arrivedAt: Instant,
    ) {
        appender.appendBound(tenant, RecordKind.EVIDENCE_PAYLOAD, docUuid, version, eventId, content, arrivedAt)
        dsl
            .insertInto(SEALED_PAYLOAD)
            .set(SEALED_PAYLOAD.TENANT_ID, tenant.value)
            .set(SEALED_PAYLOAD.DOC_UUID, docUuid)
            .set(SEALED_PAYLOAD.VERSION, version)
            .set(SEALED_PAYLOAD.SOURCE_EVENT_ID, eventId)
            .set(SEALED_PAYLOAD.RECORDED_AT, arrivedAt.utc())
            .onConflict(SEALED_PAYLOAD.TENANT_ID, SEALED_PAYLOAD.SOURCE_EVENT_ID)
            .doNothing()
            .execute()
    }
}
