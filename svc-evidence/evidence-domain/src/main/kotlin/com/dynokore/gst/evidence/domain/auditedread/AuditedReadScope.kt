package com.dynokore.gst.evidence.domain.auditedread

import java.util.UUID

/**
 * The audit entry text of an audited read (INV-Q-7207 A item 2; INV-REQ-725, INV-REQ-989). Pure: no state, no clock.
 * Every value is percent-encoded as UTF-8 so that the scope text is injective.
 */
object AuditedReadScope {
    const val ACK_ID_MAX_LENGTH = 100
    private const val HEX = "0123456789ABCDEF"
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0x0F

    /** RFC 3986 unreserved characters: A-Z a-z 0-9 - . _ ~ (all ASCII). */
    private val UNRESERVED: Set<Int> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).map { it.code }.toSet()

    fun entryOf(call: AuditedCall): AuditedReadEntry =
        when (call) {
            is AuditedCall.AcknowledgementChain -> {
                require(call.ackId.length <= ACK_ID_MAX_LENGTH) { "ackId is longer than $ACK_ID_MAX_LENGTH characters" }
                AuditedReadEntry(
                    AuditedOperation.GET_ACKNOWLEDGEMENT_CHAIN.operationId,
                    "acknowledgement:" + encode("ackId", call.ackId),
                )
            }

            is AuditedCall.ReverseLookup -> {
                AuditedReadEntry(
                    AuditedOperation.REVERSE_LOOKUP.operationId,
                    "lookup:" +
                        ReverseLookupParameter.entries
                            .filter { it in call.parameters }
                            .joinToString("&") {
                                it.parameterName + "=" +
                                    encode(it.parameterName, call.parameters.getValue(it))
                            },
                )
            }

            is AuditedCall.AuditPackCreate -> {
                AuditedReadEntry(
                    AuditedOperation.CREATE_AUDIT_PACK_EXPORT.operationId,
                    packScope(call.entityRef, call.periodRef),
                )
            }

            is AuditedCall.AuditPackRead -> {
                AuditedReadEntry(
                    AuditedOperation.GET_AUDIT_PACK_EXPORT.operationId,
                    packScope(call.entityRef, call.periodRef) + "/pack:" + packText(call.packId),
                )
            }
        }

    private fun packScope(
        entityRef: String,
        periodRef: String,
    ): String = "entity:" + encode("entityRef", entityRef) + "/period:" + encode("periodRef", periodRef)

    private fun packText(packId: UUID): String = packId.toString().lowercase()

    private fun encode(
        name: String,
        value: String,
    ): String {
        require(value.isNotBlank()) { "$name must not be blank" }
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and BYTE_MASK
            if (b in UNRESERVED) {
                out.append(b.toChar())
            } else {
                out.append('%').append(HEX[b shr NIBBLE]).append(HEX[b and NIBBLE_MASK])
            }
        }
        return out.toString()
    }
}
