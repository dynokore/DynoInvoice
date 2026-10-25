package com.dynokore.gst.evidence.application.chain

import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock

/**
 * Stores the locator of a document version from the event that creates it and then drains the records that arrived
 * before it (INV-Q-7213 A (1) and (3)). Runs in the consumer's tenant transaction.
 */
class LocatorRecorder(
    private val locators: LocatorStore,
    private val appender: RecordAppender,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** True when the locator was stored; a repeat (the same event id or the same version) stores and drains nothing. */
    fun record(
        tenant: TenantId,
        eventId: String,
        locator: DocumentLocator,
    ): Boolean {
        locators.lock(tenant, locator.docUuid, locator.version)
        if (!locators.insert(tenant, eventId, locator, clock.instant())) return false
        appender.drainPending(tenant, locator.docUuid, locator.version)
        return true
    }
}
