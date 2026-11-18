package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.domain.transitions.AcknowledgementDeadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The facts the cap is computed from: the filing due date (null when unknown) and the safety margin in force. */
data class AcknowledgementBasis(
    val dueDate: LocalDate?,
    val margin: SafetyMarginAnswer,
)

/** Reads the due date and margin of a document; may throw a RuntimeException when an input is unreadable. */
fun interface AcknowledgementInputs {
    fun of(
        tenant: TenantId,
        docUuid: String,
    ): AcknowledgementBasis
}

/** Computes the acknowledgement deadline of an accepted version (INV-AC-22760); reads no clock and no configuration. */
class AcknowledgementDeadlines(
    private val inputs: AcknowledgementInputs,
    private val zone: ZoneId,
    private val threshold: Duration,
) {
    fun at(
        tenant: TenantId,
        docUuid: String,
        acceptedAt: Instant,
    ): Instant = AcknowledgementDeadline.of(acceptedAt, threshold, cap(tenant, docUuid))

    private fun cap(
        tenant: TenantId,
        docUuid: String,
    ): Instant? {
        val basis = read(tenant, docUuid)
        val dueDate = basis?.dueDate
        val answer = basis?.margin
        return if (dueDate == null ||
            answer !is SafetyMarginAnswer.Known
        ) {
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
            LOG.log(System.Logger.Level.WARNING, "acknowledgement inputs unreadable: ${e.javaClass.name}")
            null
        }

    private companion object {
        val LOG: System.Logger = System.getLogger(AcknowledgementDeadlines::class.java.name)
    }
}
