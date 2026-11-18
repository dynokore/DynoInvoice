package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.queue.AcknowledgementBasis
import com.dynokore.gst.submission.application.queue.AcknowledgementInputs
import com.dynokore.gst.submission.application.queue.DueMarginCap
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.ZoneId

/**
 * The instant from which a pending credit note no longer waits for its original (INV-REQ-709, INV-REQ-721): the due
 * date minus the safety margin. Null when the deadline is unknown: no due date, no known margin or an unreadable input.
 */
class ReleaseAloneAt(
    private val inputs: AcknowledgementInputs,
    private val zone: ZoneId,
) {
    fun of(
        tenant: TenantId,
        docUuid: String,
    ): Instant? {
        val basis = read(tenant, docUuid)
        val dueDate = basis?.dueDate
        val answer = basis?.margin
        return if (dueDate == null || answer !is SafetyMarginAnswer.Known) {
            null
        } else {
            DueMarginCap.of(dueDate, answer.margin, zone)
        }
    }

    // Only a RuntimeException is an unreadable input; an Error propagates.
    @Suppress("TooGenericExceptionCaught")
    private fun read(
        tenant: TenantId,
        docUuid: String,
    ): AcknowledgementBasis? =
        try {
            inputs.of(tenant, docUuid)
        } catch (e: RuntimeException) {
            LOG.log(System.Logger.Level.WARNING, "release-alone inputs unreadable: ${e.javaClass.name}")
            null
        }

    private companion object {
        val LOG: System.Logger = System.getLogger(ReleaseAloneAt::class.java.name)
    }
}
