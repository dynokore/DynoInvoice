package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.payload.QuarantineOrigin
import com.dynokore.gst.evidence.application.payload.RefusedDespatch
import com.dynokore.gst.evidence.application.payload.SealSignals
import com.dynokore.platform.tenancykernel.TenantId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/** Logs the seal signals as event lines (12-factor XI): identifiers and the reason only, never payload bytes. */
class LoggingSealSignals(
    private val log: Logger = LoggerFactory.getLogger(LoggingSealSignals::class.java),
) : SealSignals {
    override fun despatchRefused(
        tenant: TenantId,
        refused: RefusedDespatch,
    ) {
        log.warn(
            "despatch refused tenant={} event={} docUuid={} version={} reason={}",
            tenant.value,
            refused.eventId,
            refused.despatch.docUuid,
            refused.despatch.version,
            refused.reason,
        )
    }

    override fun payloadQuarantined(
        tenant: TenantId,
        causedByEventId: String,
        docUuid: UUID,
        version: Int,
        origin: QuarantineOrigin,
    ) {
        log.warn(
            "payload quarantined tenant={} event={} docUuid={} version={} origin={}",
            tenant.value,
            causedByEventId,
            docUuid,
            version,
            origin,
        )
    }
}
