package com.dynokore.gst.canonical.domain.netting

import java.util.UUID

/** `documentFunction` of the ingestion contract (`ingestion/schemas/transaction-request.schema.json`). */
enum class DocumentFunction { INVOICE, CREDIT_NOTE, DEBIT_NOTE, BILL_FOR_PAYMENT }

/** The lookup result for one declared key: the recorded document the key names. */
data class RecordedDocument(
    val docUuid: UUID,
    val entityId: UUID,
    val documentFunction: DocumentFunction,
)

/** A recorded document whose current version lists keys, as a candidate to net a credit note. */
data class DeclaringDocument(
    val docUuid: UUID,
    val entityId: UUID,
    val documentFunction: DocumentFunction,
    val currentKeys: List<String>,
)

/** Why a declared key did not become a netted credit note Doc UUID. */
enum class Why { NOT_RECORDED, NOT_A_CREDIT_NOTE, OTHER_ENTITY }

data class Unresolved(
    val key: String,
    val why: Why,
)

/**
 * [resolved]: the Doc UUIDs of `nettedCreditNoteDocUuids`, in key order;
 * [unresolved]: the other keys, in key order.
 */
data class RevisionLinks(
    val resolved: List<UUID>,
    val unresolved: List<Unresolved>,
)

/** The `nettedByDocUuid` decision of a credit note. */
sealed interface CreditNoteNetting

data class NettedBy(
    val docUuid: UUID,
) : CreditNoteNetting

data object NotNetted : CreditNoteNetting

/** Two or more revised invoices declare the credit note: no guess (INV-T-6315). */
data object Ambiguous : CreditNoteNetting

/**
 * INV-REQ-558 behavior 4: the System never infers netting, the ERP declares it. These functions only decide which
 * declared keys become links; they keep no state and read nothing but their arguments. Keys are compared exactly.
 */
object NettingLinks {
    fun forRevision(
        entityId: UUID,
        keys: List<String>,
        recorded: Map<String, RecordedDocument>,
    ): RevisionLinks {
        val resolved = mutableListOf<UUID>()
        val unresolved = mutableListOf<Unresolved>()
        for (key in keys) {
            val doc = recorded[key]
            val why =
                when {
                    doc == null -> Why.NOT_RECORDED
                    doc.documentFunction != DocumentFunction.CREDIT_NOTE -> Why.NOT_A_CREDIT_NOTE
                    doc.entityId != entityId -> Why.OTHER_ENTITY
                    else -> null
                }
            if (why == null) resolved += recorded.getValue(key).docUuid else unresolved += Unresolved(key, why)
        }
        return RevisionLinks(resolved, unresolved)
    }

    fun forCreditNote(
        entityId: UUID,
        sourceKey: String,
        declaring: List<DeclaringDocument>,
    ): CreditNoteNetting {
        val matches =
            declaring.filter {
                it.entityId == entityId && it.documentFunction == DocumentFunction.INVOICE &&
                    sourceKey in it.currentKeys
            }
        return when (matches.size) {
            0 -> NotNetted
            1 -> NettedBy(matches.single().docUuid)
            else -> Ambiguous
        }
    }
}
