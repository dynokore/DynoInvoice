package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AcknowledgementLocator.Companion.ACKNOWLEDGEMENT_LOCATOR
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocator
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocatorStore
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.util.UUID

/** `acknowledgement_locator` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqAcknowledgementLocatorStore(
    private val dsl: DSLContext,
) : AcknowledgementLocatorStore {
    override fun insert(
        tenant: TenantId,
        eventId: String,
        ackId: String,
        docUuid: UUID,
        version: Int,
        recordedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(ACKNOWLEDGEMENT_LOCATOR)
            .set(ACKNOWLEDGEMENT_LOCATOR.TENANT_ID, tenant.value)
            .set(ACKNOWLEDGEMENT_LOCATOR.ACK_ID, ackId)
            .set(ACKNOWLEDGEMENT_LOCATOR.DOC_UUID, docUuid)
            .set(ACKNOWLEDGEMENT_LOCATOR.VERSION, version)
            .set(ACKNOWLEDGEMENT_LOCATOR.SOURCE_EVENT_ID, eventId)
            .set(ACKNOWLEDGEMENT_LOCATOR.RECORDED_AT, recordedAt.utc())
            .onConflictDoNothing()
            .execute() == 1

    override fun find(
        tenant: TenantId,
        ackId: String,
    ): AcknowledgementLocator? =
        dsl
            .select(ACKNOWLEDGEMENT_LOCATOR.ACK_ID, ACKNOWLEDGEMENT_LOCATOR.DOC_UUID, ACKNOWLEDGEMENT_LOCATOR.VERSION)
            .from(ACKNOWLEDGEMENT_LOCATOR)
            .where(ACKNOWLEDGEMENT_LOCATOR.TENANT_ID.eq(tenant.value))
            .and(ACKNOWLEDGEMENT_LOCATOR.ACK_ID.eq(ackId))
            .fetchOne {
                AcknowledgementLocator(
                    checkNotNull(it[ACKNOWLEDGEMENT_LOCATOR.ACK_ID]),
                    checkNotNull(it[ACKNOWLEDGEMENT_LOCATOR.DOC_UUID]),
                    checkNotNull(it[ACKNOWLEDGEMENT_LOCATOR.VERSION]),
                )
            }
}
