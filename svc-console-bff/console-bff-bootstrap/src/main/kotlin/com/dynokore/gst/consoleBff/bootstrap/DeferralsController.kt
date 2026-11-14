package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.deferral.DeferResult
import com.dynokore.gst.consoleBff.application.deferral.DeferTarget
import com.dynokore.gst.consoleBff.application.deferral.UndeferResult
import com.dynokore.gst.consoleBff.application.deferral.UndeferTarget
import com.dynokore.gst.consoleBff.contract.api.DeferralsApi
import com.dynokore.gst.consoleBff.contract.model.DeferDocumentRequest
import com.dynokore.gst.consoleBff.domain.deferral.Deferral
import com.dynokore.gst.consoleBff.domain.deferral.DeferralKind
import com.dynokore.gst.consoleBff.domain.deferral.DeferralTarget
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.consoleBff.contract.model.Deferral as WireDeferral
import com.dynokore.gst.consoleBff.contract.model.DeferralTarget as WireTarget

/** A refusal carrying its closed problem answer; [DeferralsProblemAdvice] writes it. */
class DeferralsRefusal(
    val answer: StoredResponse,
) : RuntimeException(null, null, false, false)

/**
 * deferDocument and undeferDocument (INV-REQ-960): the use cases decide the permission `exception.defer` per request
 * (INV-Q-10027); this class compares no role. Only the DOCUMENT and GROUP kinds are accepted (the enum is extensible).
 */
@RestController
open class DeferralsController(
    private val deferTarget: DeferTarget,
    private val undeferTarget: UndeferTarget,
) : DeferralsApi {
    override fun deferDocument(
        entityRef: String,
        deferDocumentRequest: DeferDocumentRequest,
    ): ResponseEntity<WireDeferral> {
        val (tenant, principal) = caller()
        val target = targetOf(deferDocumentRequest.target)
        return when (val result = deferTarget.execute(tenant, principal, entityRef, target)) {
            is DeferResult.Deferred -> ResponseEntity.ok(toWire(result.deferral))
            DeferResult.NotFound -> refuse(Problems.notFound(correlation()))
            DeferResult.Forbidden -> refuse(Problems.accessDenied(correlation()))
        }
    }

    override fun undeferDocument(
        entityRef: String,
        deferralId: UUID,
    ): ResponseEntity<WireDeferral> {
        val (tenant, principal) = caller()
        return when (val result = undeferTarget.execute(tenant, principal, entityRef, deferralId)) {
            is UndeferResult.Undone -> ResponseEntity.ok(toWire(result.deferral))
            UndeferResult.NotFound -> refuse(Problems.notFound(correlation()))
            UndeferResult.Forbidden -> refuse(Problems.accessDenied(correlation()))
        }
    }

    private fun caller(): Pair<String, String> {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val principal = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenant = request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String
        if (principal == null || tenant == null) refuse(Problems.unauthenticated(correlation()))
        return tenant to principal
    }

    private fun targetOf(wire: WireTarget): DeferralTarget {
        val kind =
            when (wire.kind.value) {
                "DOCUMENT" -> DeferralKind.DOCUMENT
                "GROUP" -> DeferralKind.GROUP
                else -> refuse(Problems.contractViolation("target kind is not a known value", correlation()))
            }
        return try {
            DeferralTarget(kind, wire.ref)
        } catch (_: IllegalArgumentException) {
            refuse(Problems.contractViolation("target ref is out of bounds", correlation()))
        }
    }

    private fun toWire(deferral: Deferral): WireDeferral =
        WireDeferral(
            deferralId = deferral.deferralId,
            entityRef = deferral.entityRef,
            target = WireTarget(WireTarget.Kind.forValue(deferral.target.kind.name), deferral.target.ref),
            state = WireDeferral.State.forValue(deferral.state.name),
            deferredBy = deferral.deferredBy,
            deferredAt = deferral.deferredAt.atOffset(ZoneOffset.UTC),
        )

    private fun refuse(answer: StoredResponse): Nothing = throw DeferralsRefusal(answer)

    private fun correlation(): String = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
}
