package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.application.ReceiveApNotification
import com.dynokore.gst.transport.application.ReceiveResult
import com.dynokore.gst.transport.contract.api.NotificationsApi
import com.dynokore.gst.transport.contract.model.ApNotification
import com.dynokore.gst.transport.contract.model.ApNotificationResult
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.NotificationReason
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import com.dynokore.gst.transport.domain.ApNotification as ApNotificationInput

/**
 * The `notifications` operations of the transport contract (TPL-08). `receiveApNotification` (INV-REQ-585): the sender
 * was verified by `ApCallbackSignatureFilter` before the body was parsed; this method refuses a request the filter did
 * not verify for the adapter of its path (401 SRT-003), then records and translates the notification once.
 */
@RestController
open class NotificationsController(
    private val receive: ReceiveApNotification,
) : NotificationsApi {
    override fun receiveApNotification(
        adapterId: String,
        apNotification: ApNotification,
        xAPTimestamp: Int?,
        xAPSignature: String?,
    ): ResponseEntity<ApNotificationResult> {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        if (request.getAttribute(ApCallbackSignatureFilter.VERIFIED_ADAPTER) != adapterId) {
            throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
        }
        return respond(apNotification, receive.receive(AdapterId(adapterId), input(apNotification)))
    }

    private fun respond(
        body: ApNotification,
        result: ReceiveResult,
    ): ResponseEntity<ApNotificationResult> {
        val refusal =
            when (result) {
                is ReceiveResult.Processed -> null

                is ReceiveResult.Invalid -> ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, result.reason)

                ReceiveResult.OwnerNotFound -> ApiRefusal(ClosedErrorCode.NOT_FOUND)

                // INV-Q-9676 A answers 503 with the API-20 envelope; no catalogue code is fixed for it (gap, MR)
                ReceiveResult.InboundNotSupported -> error("INBOUND_NETWORK_DOCUMENT: the 503 code is not ruled")
            }
        if (refusal != null) throw refusal
        val recorded = (result as ReceiveResult.Processed).recorded
        return ResponseEntity.ok(ApNotificationResult(body.apNotificationId, recorded))
    }

    private fun input(body: ApNotification) =
        ApNotificationInput(
            apNotificationId = body.apNotificationId,
            kind = body.kind.value,
            docUuid = body.docUuid,
            version = body.version,
            acknowledgementId = body.acknowledgementId,
            reasons = body.reasons?.map { NotificationReason(it.code, it.message) },
        )
}
