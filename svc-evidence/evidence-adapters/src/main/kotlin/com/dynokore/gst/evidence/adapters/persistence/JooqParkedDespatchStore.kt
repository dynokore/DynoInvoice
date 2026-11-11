package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.ParkedDespatch.Companion.PARKED_DESPATCH
import com.dynokore.gst.evidence.application.payload.ParkedDespatch
import com.dynokore.gst.evidence.application.payload.ParkedDespatchStore
import com.dynokore.gst.evidence.domain.payload.Despatch
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Clock
import java.util.UUID

/** `parked_despatch` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqParkedDespatchStore(
    private val dsl: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) : ParkedDespatchStore {
    override fun park(
        tenant: TenantId,
        parked: ParkedDespatch,
    ): Boolean =
        dsl
            .insertInto(PARKED_DESPATCH)
            .set(PARKED_DESPATCH.TENANT_ID, tenant.value)
            .set(PARKED_DESPATCH.EVENT_ID, parked.eventId)
            .set(PARKED_DESPATCH.DOC_UUID, parked.despatch.docUuid)
            .set(PARKED_DESPATCH.VERSION, parked.despatch.version)
            .set(PARKED_DESPATCH.ATTEMPT_NUMBER, parked.despatch.attemptNumber)
            .set(PARKED_DESPATCH.PAYLOAD_SHA256, parked.despatch.payloadSha256)
            .set(PARKED_DESPATCH.DESPATCHED_AT, parked.despatch.despatchedAt.utc())
            .set(PARKED_DESPATCH.RECORDED_AT, clock.instant().utc())
            .onConflict(PARKED_DESPATCH.TENANT_ID, PARKED_DESPATCH.EVENT_ID)
            .doNothing()
            .execute() == 1

    override fun parkedFor(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): List<ParkedDespatch> =
        dsl
            .selectFrom(PARKED_DESPATCH)
            .where(
                PARKED_DESPATCH.TENANT_ID.eq(tenant.value),
                PARKED_DESPATCH.DOC_UUID.eq(docUuid),
                PARKED_DESPATCH.VERSION.eq(version),
            ).orderBy(PARKED_DESPATCH.ARRIVAL_SEQ)
            .fetch()
            .map {
                ParkedDespatch(
                    checkNotNull(it.eventId),
                    Despatch(
                        checkNotNull(it.docUuid),
                        checkNotNull(it.version),
                        checkNotNull(it.attemptNumber),
                        checkNotNull(it.payloadSha256),
                        checkNotNull(it.despatchedAt).toInstant(),
                    ),
                )
            }
}
