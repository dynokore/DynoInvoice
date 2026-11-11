package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.HeldPayload.Companion.HELD_PAYLOAD
import com.dynokore.gst.evidence.application.payload.HeldPayloadStore
import com.dynokore.gst.evidence.domain.payload.HeldPayload
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.util.UUID

/** `held_payload` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqHeldPayloadStore(
    private val dsl: DSLContext,
) : HeldPayloadStore {
    /** The `held-payload:` prefix keeps this lock apart from the locator lock of the same version. */
    override fun lock(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ) {
        val key = DSL.`val`("held-payload:${tenant.value}:$docUuid:$version")
        val hash = DSL.function(DSL.name("hashtextextended"), SQLDataType.BIGINT, key, DSL.`val`(0L))
        dsl.select(DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, hash)).fetch()
    }

    override fun insert(
        tenant: TenantId,
        eventId: String,
        held: HeldPayload,
        recordedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(HELD_PAYLOAD)
            .set(HELD_PAYLOAD.TENANT_ID, tenant.value)
            .set(HELD_PAYLOAD.DOC_UUID, held.docUuid)
            .set(HELD_PAYLOAD.VERSION, held.version)
            .set(HELD_PAYLOAD.EVENT_ID, eventId)
            .set(HELD_PAYLOAD.PAYLOAD_BYTES, held.payloadBytes())
            .set(HELD_PAYLOAD.PAYLOAD_SHA256, held.payloadSha256)
            .set(HELD_PAYLOAD.RECORDED_AT, recordedAt.utc())
            .onConflict(HELD_PAYLOAD.TENANT_ID, HELD_PAYLOAD.DOC_UUID, HELD_PAYLOAD.VERSION)
            .doNothing()
            .execute() == 1

    override fun find(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): HeldPayload? =
        dsl
            .selectFrom(HELD_PAYLOAD)
            .where(
                HELD_PAYLOAD.TENANT_ID.eq(tenant.value),
                HELD_PAYLOAD.DOC_UUID.eq(docUuid),
                HELD_PAYLOAD.VERSION.eq(version),
            ).fetchOne()
            ?.let {
                HeldPayload(
                    checkNotNull(it.docUuid),
                    checkNotNull(it.version),
                    checkNotNull(it.payloadBytes),
                    checkNotNull(it.payloadSha256),
                )
            }

    override fun remove(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ) {
        dsl
            .deleteFrom(HELD_PAYLOAD)
            .where(
                HELD_PAYLOAD.TENANT_ID.eq(tenant.value),
                HELD_PAYLOAD.DOC_UUID.eq(docUuid),
                HELD_PAYLOAD.VERSION.eq(version),
            ).execute()
    }
}
