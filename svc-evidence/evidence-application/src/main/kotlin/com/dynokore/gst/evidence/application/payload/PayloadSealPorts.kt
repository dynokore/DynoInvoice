package com.dynokore.gst.evidence.application.payload

import com.dynokore.gst.evidence.domain.payload.Despatch
import com.dynokore.gst.evidence.domain.payload.HeldPayload
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/** Payloads held from PayloadReady until their despatch arrives; one row per Doc UUID and version. */
interface HeldPayloadStore {
    /** Serialises the two writers of one version (the precedent is LocatorStore.lock). */
    fun lock(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    )

    /** Returns false and stores nothing when the Doc UUID and version already have a row: the first payload wins. */
    fun insert(
        tenant: TenantId,
        eventId: String,
        held: HeldPayload,
        recordedAt: Instant,
    ): Boolean

    fun find(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): HeldPayload?

    /** Called only after the same payload was written to the quarantine store in the same call. */
    fun remove(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    )
}

data class ParkedDespatch(
    val eventId: String,
    val despatch: Despatch,
)

interface ParkedDespatchStore {
    /** Returns false and stores nothing when the event id is already parked. */
    fun park(
        tenant: TenantId,
        parked: ParkedDespatch,
    ): Boolean

    /** In order of arrival. */
    fun parkedFor(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): List<ParkedDespatch>
}

enum class QuarantineOrigin { RECEIVED, STORED }

/**
 * Dead Letter Channel for payloads that fail their own digest. Idempotent by cause event id and origin; nothing in
 * it is ever sealed.
 */
interface PayloadQuarantineStore {
    fun quarantine(
        tenant: TenantId,
        causedByEventId: String,
        held: HeldPayload,
        origin: QuarantineOrigin,
        recordedAt: Instant,
    )
}

/** Observable signals; arguments carry identifiers and reasons only, never payload bytes. */
interface SealSignals {
    fun despatchRefused(
        tenant: TenantId,
        refused: RefusedDespatch,
    )

    fun payloadQuarantined(
        tenant: TenantId,
        causedByEventId: String,
        docUuid: UUID,
        version: Int,
        origin: QuarantineOrigin,
    )
}

/** Appends the sealed content to the evidence chain; idempotent by [eventId]. */
interface SealedPayloadSink {
    @Suppress("LongParameterList") // the signature of INV-T-13389, as RecordAppender.appendBound
    fun append(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
        eventId: String,
        content: ByteArray,
        arrivedAt: Instant,
    )
}
