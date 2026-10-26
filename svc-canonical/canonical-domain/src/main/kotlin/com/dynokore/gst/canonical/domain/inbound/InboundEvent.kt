package com.dynokore.gst.canonical.domain.inbound

import com.dynokore.gst.canonical.domain.document.DocumentFunction
import java.time.LocalDate
import java.util.UUID

/** The buyer's disposition of a network receipt (`networkReceipt.buyerDisposition`, INV-REQ-428). */
enum class BuyerDisposition { PENDING, ACCEPTED, REJECTED }

/** The pack rule `inboundDocUuidRule` (INV-REQ-569). */
enum class InboundDocUuidRule { ADOPT_SUPPLIER, OWN }

enum class InboundKind { RECEIPT, POSTING }

/**
 * One purchase event to correlate (INV-REQ-429). [documentNumber] is the number as the caller extracted it:
 * `networkReceipt.supplierDocumentNumber` on a receipt, `documentNumber` on a posting. The receipt-only fields are
 * null on a posting. The direction PURCHASE is implied by the type.
 */
data class InboundEvent(
    val kind: InboundKind,
    val entityId: UUID,
    val documentFunction: DocumentFunction,
    val supplierScheme: String,
    val supplierValue: String,
    val documentNumber: String,
    val transactionDate: LocalDate,
    val supplierEndpointId: String? = null,
    val supplierDocUuid: UUID? = null,
    val buyerDisposition: BuyerDisposition? = null,
)

/** An already recorded document a lookup returned; [recordedSupplierDocUuid] is the supplier UUID bound to it, if any. */
data class Existing(
    val docUuid: UUID,
    val recordedSupplierDocUuid: UUID?,
)
