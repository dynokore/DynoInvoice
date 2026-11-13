package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.application.arrival.ArrivalPlanning
import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Consumes `PayloadReady` (INV-REQ-816, ruling INV-Q-7266 A flow step (1)). It runs in the consumer's transaction, so
 * the inbox row, the `submission` row and the queue item commit together (`EventHandler`). The key of the duplicate
 * check is the primary key (`doc_uuid`, `version`) of `submission` (INV-Q-7268 A): the insert is `ON CONFLICT DO
 * NOTHING`, and only an inserted row is enqueued. A duplicate is discarded here, before any transport call; this class
 * holds no transport client or port (the call belongs to the attempts slice, after the commit of the SENT transaction).
 *
 * A payload whose SHA-256 over the decoded bytes differs from `payloadSha256` is a [ContractViolation] (dead letter at
 * once). A second delivery of a stored version with another `payloadSha256` is a [ContractViolation] too: a stored
 * version is never replaced and the conflict is not discarded silently.
 */
class PayloadReadyHandler(
    private val tenants: TenantContext,
    private val store: SubmissionStore,
    private val queue: SubmissionQueue,
    private val reader: PayloadReadyDataReader,
    private val inFlight: InFlightStore,
    private val planning: ArrivalPlanning,
    private val raising: CodeRaising,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val docUuid = data.docUuid
        val version = data.version
        val bytes = data.payloadBytes
        val declared = data.payloadSha256
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        if (actual != declared) {
            throw ContractViolation("PayloadReady $docUuid version $version: payloadSha256 differs from the bytes")
        }
        val submissionType = SubmissionTypeCode.fromWire(data.submissionType)
        val tenant = tenants.current()
        val inserted =
            store.insertReady(
                tenant,
                NewReadySubmission(
                    docUuid,
                    version,
                    event.entity.value,
                    event.transactionDate,
                    bytes,
                    declared,
                    submissionType,
                ),
            )
        if (inserted == 0) {
            if (store.storedPayloadSha256(tenant, docUuid, version) != declared) {
                throw ContractViolation(
                    "PayloadReady $docUuid version $version is stored with another payloadSha256: " +
                        "a stored version is never replaced",
                )
            }
            return HandleOutcome.IGNORED
        }
        val earlier = inFlight.lockVersions(tenant, docUuid).filter { it.version < version }.map { it.state }
        val plan = planning.plan(tenant, docUuid, earlier)
        queue.enqueue(docUuid, version, plan.lane)
        if (plan.codes.isNotEmpty()) {
            // A non-empty code list implies the register keys are set (ArrivalPlanning.planFirst).
            val entityRef = checkNotNull(plan.entityRef)
            val periodRef = checkNotNull(plan.periodRef)
            raising.raise(tenant, docUuid, version, entityRef, periodRef, plan.codes)
        }
        return HandleOutcome.APPLIED
    }
}
