package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PayloadQuarantine.Companion.PAYLOAD_QUARANTINE
import com.dynokore.gst.evidence.application.payload.PayloadQuarantineStore
import com.dynokore.gst.evidence.application.payload.QuarantineOrigin
import com.dynokore.gst.evidence.domain.payload.HeldPayload
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant

/** `payload_quarantine` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqPayloadQuarantineStore(
    private val dsl: DSLContext,
) : PayloadQuarantineStore {
    override fun quarantine(
        tenant: TenantId,
        causedByEventId: String,
        held: HeldPayload,
        origin: QuarantineOrigin,
        recordedAt: Instant,
    ) {
        dsl
            .insertInto(PAYLOAD_QUARANTINE)
            .set(PAYLOAD_QUARANTINE.TENANT_ID, tenant.value)
            .set(PAYLOAD_QUARANTINE.CAUSED_BY_EVENT_ID, causedByEventId)
            .set(PAYLOAD_QUARANTINE.ORIGIN, origin.name)
            .set(PAYLOAD_QUARANTINE.DOC_UUID, held.docUuid)
            .set(PAYLOAD_QUARANTINE.VERSION, held.version)
            .set(PAYLOAD_QUARANTINE.PAYLOAD_BYTES, held.payloadBytes())
            .set(PAYLOAD_QUARANTINE.PAYLOAD_SHA256, held.payloadSha256)
            .set(PAYLOAD_QUARANTINE.RECORDED_AT, recordedAt.utc())
            .onConflict(PAYLOAD_QUARANTINE.TENANT_ID, PAYLOAD_QUARANTINE.CAUSED_BY_EVENT_ID, PAYLOAD_QUARANTINE.ORIGIN)
            .doNothing()
            .execute()
    }
}
