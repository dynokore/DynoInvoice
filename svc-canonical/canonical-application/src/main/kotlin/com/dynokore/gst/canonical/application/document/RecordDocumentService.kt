package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.application.source.RecordedResult
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.source.SourceKey
import java.time.LocalDate
import java.util.UUID

data class PartyIdentifier(
    val scheme: String,
    val value: String,
)

data class RecordDocumentCommand(
    val tenantId: UUID,
    val entityRef: String,
    val sourceSystem: String,
    val sourceKey: String,
    val sourceRevision: Long,
    val transactionDate: LocalDate,
    val direction: Direction?,
    val supplier: PartyIdentifier,
    val buyer: PartyIdentifier,
)

/** Builds the result text of a recorded document; implemented outside this service. */
fun interface DocumentResultBuilder {
    fun build(
        command: RecordDocumentCommand,
        docUuid: UUID,
    ): String
}

/**
 * Turns a parsed recordDocument request into the pipeline call. The counterparty of the entity is the buyer of a SALE
 * and the supplier of a PURCHASE (the entity is the other party of its own document). The lookup is built only when
 * the direction is present, the chosen identifier is not blank and the constitution is known; otherwise it is null and
 * nothing is guessed.
 */
class RecordDocumentService(
    private val pipeline: RecordDocumentPipeline,
    private val constitutions: EntityConstitutions,
    private val builder: DocumentResultBuilder,
) {
    fun record(command: RecordDocumentCommand): RecordedResult {
        val key = SourceKey(command.tenantId, command.sourceSystem, command.sourceKey, command.sourceRevision)
        return pipeline.record(key, lookupOf(command)) { docUuid -> builder.build(command, docUuid) }
    }

    private fun lookupOf(command: RecordDocumentCommand): CounterpartyLookup? {
        val party =
            when (command.direction) {
                Direction.SALE -> command.buyer
                Direction.PURCHASE -> command.supplier
                null -> null
            }
        return party
            ?.takeIf { it.scheme.isNotBlank() && it.value.isNotBlank() }
            ?.let { p ->
                constitutions.constitutionOf(command.tenantId, command.entityRef)?.let { c ->
                    CounterpartyLookup(command.entityRef, c, p.scheme, p.value, command.transactionDate)
                }
            }
    }
}
