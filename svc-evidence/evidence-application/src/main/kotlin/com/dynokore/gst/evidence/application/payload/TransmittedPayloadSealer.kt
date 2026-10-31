package com.dynokore.gst.evidence.application.payload

import com.dynokore.gst.evidence.domain.payload.Despatch
import com.dynokore.gst.evidence.domain.payload.HeldPayload
import com.dynokore.gst.evidence.domain.payload.SealDecision
import com.dynokore.gst.evidence.domain.payload.SealRefusal
import com.dynokore.gst.evidence.domain.payload.TransmittedPayloadSeal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.util.UUID

enum class SealOutcome { SEALED, PARKED, REFUSED }

data class DespatchResult(
    val outcome: SealOutcome,
    val refusal: SealRefusal?,
)

enum class PayloadOutcome { STORED, DUPLICATE, CONFLICT, REJECTED }

data class RefusedDespatch(
    val eventId: String,
    val despatch: Despatch,
    val reason: SealRefusal,
)

data class PayloadReadyResult(
    val outcome: PayloadOutcome,
    val rejection: SealRefusal?,
    val sealedEventIds: List<String>,
    val refused: List<RefusedDespatch>,
)

data class RedriveResult(
    val sealedEventIds: List<String>,
    val refused: List<RefusedDespatch>,
)

/**
 * Seals a despatched version whichever of PayloadReady and SubmissionDespatched arrives first. Runs in the caller's
 * tenant transaction.
 */
class TransmittedPayloadSealer(
    private val held: HeldPayloadStore,
    private val parked: ParkedDespatchStore,
    private val quarantine: PayloadQuarantineStore,
    private val sink: SealedPayloadSink,
    private val signals: SealSignals,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun onDespatched(
        tenant: TenantId,
        eventId: String,
        despatch: Despatch,
    ): DespatchResult {
        held.lock(tenant, despatch.docUuid, despatch.version)
        val stored = held.find(tenant, despatch.docUuid, despatch.version)
        return when (val decision = TransmittedPayloadSeal.decide(stored, despatch)) {
            is SealDecision.Seal -> {
                sink.append(
                    tenant,
                    despatch.docUuid,
                    despatch.version,
                    eventId,
                    decision.payload.contentJson(),
                    clock.instant(),
                )
                DespatchResult(SealOutcome.SEALED, null)
            }

            SealDecision.AwaitPayload -> {
                parked.park(tenant, ParkedDespatch(eventId, despatch))
                DespatchResult(SealOutcome.PARKED, null)
            }

            is SealDecision.Refused -> {
                parked.park(tenant, ParkedDespatch(eventId, despatch))
                signals.despatchRefused(tenant, RefusedDespatch(eventId, despatch, decision.reason))
                DespatchResult(SealOutcome.REFUSED, decision.reason)
            }
        }
    }

    fun onPayloadReady(
        tenant: TenantId,
        eventId: String,
        payload: HeldPayload,
    ): PayloadReadyResult {
        if (!payload.bytesMatchDigest()) return reject(tenant, eventId, payload)
        held.lock(tenant, payload.docUuid, payload.version)
        return insertOrSettle(tenant, eventId, payload) ?: sealParked(tenant, payload)
    }

    private fun sealParked(
        tenant: TenantId,
        payload: HeldPayload,
    ): PayloadReadyResult {
        val stored = held.find(tenant, payload.docUuid, payload.version)
        val drained = drain(tenant, payload.docUuid, payload.version, stored)
        return PayloadReadyResult(PayloadOutcome.STORED, null, drained.sealedEventIds, drained.refused)
    }

    private fun reject(
        tenant: TenantId,
        eventId: String,
        payload: HeldPayload,
    ): PayloadReadyResult {
        quarantine.quarantine(tenant, eventId, payload, QuarantineOrigin.RECEIVED, clock.instant())
        signals.payloadQuarantined(tenant, eventId, payload.docUuid, payload.version, QuarantineOrigin.RECEIVED)
        return PayloadReadyResult(
            PayloadOutcome.REJECTED,
            SealRefusal.HELD_BYTES_DIGEST_MISMATCH,
            emptyList(),
            emptyList(),
        )
    }

    private fun insertOrSettle(
        tenant: TenantId,
        eventId: String,
        payload: HeldPayload,
    ): PayloadReadyResult? =
        if (held.insert(tenant, eventId, payload, clock.instant())) null else settleExisting(tenant, eventId, payload)

    /** The version has a row: a final result for a duplicate or conflict, null when a corrupt row was replaced. */
    private fun settleExisting(
        tenant: TenantId,
        eventId: String,
        payload: HeldPayload,
    ): PayloadReadyResult? {
        val stored =
            checkNotNull(held.find(tenant, payload.docUuid, payload.version)) {
                "held payload row lost for ${payload.docUuid} v${payload.version}"
            }
        if (stored.bytesMatchDigest()) {
            val same = stored.payloadSha256 == payload.payloadSha256
            val outcome = if (same) PayloadOutcome.DUPLICATE else PayloadOutcome.CONFLICT
            return PayloadReadyResult(outcome, null, emptyList(), emptyList())
        }
        quarantine.quarantine(tenant, eventId, stored, QuarantineOrigin.STORED, clock.instant())
        signals.payloadQuarantined(tenant, eventId, payload.docUuid, payload.version, QuarantineOrigin.STORED)
        held.remove(tenant, payload.docUuid, payload.version)
        check(held.insert(tenant, eventId, payload, clock.instant())) {
            "held payload row not insertable after removal for ${payload.docUuid} v${payload.version}"
        }
        return null
    }

    /** Operator repair path: applies the drain to the parked despatches of a version; signals each refusal again. */
    fun redrive(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): RedriveResult {
        held.lock(tenant, docUuid, version)
        val payload = held.find(tenant, docUuid, version) ?: return RedriveResult(emptyList(), emptyList())
        return drain(tenant, docUuid, version, payload)
    }

    private fun drain(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
        payload: HeldPayload?,
    ): RedriveResult {
        val sealed = mutableListOf<String>()
        val refused = mutableListOf<RefusedDespatch>()
        for (p in parked.parkedFor(tenant, docUuid, version)) {
            when (val decision = TransmittedPayloadSeal.decide(payload, p.despatch)) {
                is SealDecision.Seal -> {
                    sink.append(tenant, docUuid, version, p.eventId, decision.payload.contentJson(), clock.instant())
                    sealed += p.eventId
                }

                is SealDecision.Refused -> {
                    val r = RefusedDespatch(p.eventId, p.despatch, decision.reason)
                    signals.despatchRefused(tenant, r)
                    refused += r
                }

                SealDecision.AwaitPayload -> {
                    error("held payload row lost during drain for $docUuid v$version")
                }
            }
        }
        return RedriveResult(sealed, refused)
    }
}
