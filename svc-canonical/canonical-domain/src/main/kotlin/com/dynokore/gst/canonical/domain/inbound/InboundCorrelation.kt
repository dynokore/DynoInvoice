package com.dynokore.gst.canonical.domain.inbound

import com.dynokore.gst.canonical.domain.document.DocUuidSource
import java.util.UUID

enum class BindVia { UUID_KEY, FALLBACK_KEY }

enum class HoldReason { MULTIPLE_MATCH, SUPPLIER_UUID_CONFLICT }

sealed interface CorrelationDecision

data class CreateDocument(
    val docUuidSource: DocUuidSource,
    val adoptedDocUuid: UUID?,
) : CorrelationDecision

data class NewVersionOf(
    val docUuid: UUID,
    val via: BindVia,
) : CorrelationDecision

/** The event is bound to none and held with [CODE] (HOLD, MANUAL) until a person chooses. */
data class HoldAmbiguous(
    val reason: HoldReason,
    val candidates: List<UUID>,
) : CorrelationDecision {
    companion object {
        const val CODE = "ING-017"
    }
}

/** INV-REQ-429, INV-REQ-430, INV-REQ-569: whether an inbound event creates a document or binds to one. Stateless. */
object InboundCorrelation {
    fun decide(
        event: InboundEvent,
        uuidHits: List<Existing>,
        fallbackHits: List<Existing>,
        rule: InboundDocUuidRule,
    ): CorrelationDecision {
        if (uuidHits.size == 1) return NewVersionOf(uuidHits.single().docUuid, BindVia.UUID_KEY)
        if (uuidHits.size > 1) return HoldAmbiguous(HoldReason.MULTIPLE_MATCH, uuidHits.map { it.docUuid })
        return when (fallbackHits.size) {
            0 -> {
                create(event, rule)
            }

            1 -> {
                val hit = fallbackHits.single()
                val recorded = hit.recordedSupplierDocUuid
                if (recorded != null && event.supplierDocUuid != null && recorded != event.supplierDocUuid) {
                    HoldAmbiguous(HoldReason.SUPPLIER_UUID_CONFLICT, listOf(hit.docUuid))
                } else {
                    NewVersionOf(hit.docUuid, BindVia.FALLBACK_KEY)
                }
            }

            else -> {
                HoldAmbiguous(HoldReason.MULTIPLE_MATCH, fallbackHits.map { it.docUuid })
            }
        }
    }

    private fun create(
        event: InboundEvent,
        rule: InboundDocUuidRule,
    ): CreateDocument {
        val adopted = event.supplierDocUuid
        return if (event.kind == InboundKind.RECEIPT && adopted != null && rule == InboundDocUuidRule.ADOPT_SUPPLIER) {
            CreateDocument(DocUuidSource.SUPPLIER, adopted)
        } else {
            CreateDocument(DocUuidSource.SYSTEM, null)
        }
    }
}
