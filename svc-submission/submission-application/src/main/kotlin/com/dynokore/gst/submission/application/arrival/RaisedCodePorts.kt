package com.dynokore.gst.submission.application.arrival

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * The append-only record of raised codes. Idempotent per (tenant, docUuid, version, code); answers true only
 * for a new row. Runs in the caller's tenant transaction.
 */
interface RaisedCodes {
    fun append(
        tenant: TenantId,
        raised: RaisedCode,
        raisedAt: Instant,
    ): Boolean
}

/** Stages the DocumentCodeRaised signal through the transactional outbox, in the caller's transaction. */
interface CodeSignals {
    fun stage(raised: RaisedCode)
}
