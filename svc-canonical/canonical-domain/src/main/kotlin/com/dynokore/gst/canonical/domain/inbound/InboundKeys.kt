package com.dynokore.gst.canonical.domain.inbound

import com.dynokore.gst.canonical.domain.document.DocumentFunction
import java.util.UUID

/** The primary correlation key (entity, supplier endpoint, supplier Doc UUID). */
data class UuidKey(
    val entityId: UUID,
    val supplierEndpointId: String?,
    val supplierDocUuid: UUID,
)

/** The fallback key (entity, function, supplier, supplier document number, year of the document date). */
data class FallbackKey(
    val entityId: UUID,
    val documentFunction: DocumentFunction,
    val supplierKey: String,
    val documentNumber: String,
    val documentYear: Int,
)

/** Key builders of INV-REQ-429. Pure; nothing but the event is read. */
object InboundKeys {
    fun uuidKey(event: InboundEvent): UuidKey? {
        if (event.kind != InboundKind.RECEIPT) return null
        val uuid = event.supplierDocUuid ?: return null
        return UuidKey(event.entityId, event.supplierEndpointId, uuid)
    }

    fun fallbackKey(event: InboundEvent): FallbackKey? {
        val number = event.documentNumber.trim()
        if (event.supplierScheme.isBlank() || event.supplierValue.isBlank() || number.isEmpty()) return null
        return FallbackKey(
            entityId = event.entityId,
            documentFunction = event.documentFunction,
            supplierKey = "${ascii(event.supplierScheme)}:${ascii(event.supplierValue)}",
            documentNumber = number,
            documentYear = event.transactionDate.year,
        )
    }

    /** Trimmed and lower-cased with ASCII rules (no locale or Unicode case folding). */
    private fun ascii(value: String): String =
        buildString(value.length) { value.trim().forEach { append(if (it in 'A'..'Z') it + 32 else it) } }
}
