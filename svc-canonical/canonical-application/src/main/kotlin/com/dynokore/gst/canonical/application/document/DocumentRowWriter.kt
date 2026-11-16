package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import java.util.UUID

/** The next version could not be written because the current-version pointer moved; the record fails closed. */
class DocumentRowConflict(
    val docUuid: UUID,
    val expectedVersion: Int,
) : IllegalStateException("document row conflict for $docUuid at version $expectedVersion")

/**
 * Writes the row of a record only through the single version writer, so `CanonicalVersionCreated` is staged in the
 * same transaction as the version (transactional outbox). It takes no store: nothing is written behind the writer.
 * A version without a scope verdict or without a prescribed period is written but not announced; a later version
 * announces.
 */
class DocumentRowWriter(
    private val writer: CanonicalVersionWriter,
) {
    fun write(
        command: RecordDocumentCommand,
        docUuid: UUID,
        version: Int,
        facts: DocumentRowFacts,
    ) {
        val reason = unannouncedReason(facts)
        if (version == 1) {
            val document = newDocument(command, docUuid, facts)
            if (reason == null) {
                writer.createFirst(document, eventOf(command, docUuid, version, facts))
            } else {
                writer.createFirstUnannounced(document, reason)
            }
            return
        }
        val expected = version - 1
        val outcome =
            if (reason == null) {
                writer.next(docUuid, expected, facts.version, eventOf(command, docUuid, version, facts))
            } else {
                writer.nextUnannounced(docUuid, expected, facts.version, reason)
            }
        if (outcome is UpdateOutcome.Conflict) throw DocumentRowConflict(docUuid, expected)
    }

    private fun unannouncedReason(facts: DocumentRowFacts): UnannouncedReason? =
        when {
            facts.version.scopeVerdict == null -> UnannouncedReason.NO_SCOPE_VERDICT
            facts.prescribedPeriodRef.isEmpty() -> UnannouncedReason.NO_PRESCRIBED_PERIOD
            else -> null
        }

    private fun newDocument(
        command: RecordDocumentCommand,
        docUuid: UUID,
        facts: DocumentRowFacts,
    ): NewDocument =
        NewDocument(
            docUuid = docUuid,
            tenantId = command.tenantId,
            entityId = facts.entityId,
            businessUnitId = facts.businessUnitId,
            direction = checkNotNull(command.direction) { "direction is absent" },
            documentTypeCode = facts.documentTypeCode,
            documentNumber = checkNotNull(command.documentNumber) { "documentNumber is absent" },
            issuerPartyKey = facts.issuerPartyKey,
            fiscalYear = facts.fiscalYear,
            internalReference = facts.internalReference,
            state = facts.state,
            prescribedPeriodId = facts.prescribedPeriodId,
            first = facts.version,
        )

    private fun eventOf(
        command: RecordDocumentCommand,
        docUuid: UUID,
        version: Int,
        facts: DocumentRowFacts,
    ): CanonicalVersionCreatedFacts =
        CanonicalVersionCreatedFacts(
            docUuid = docUuid,
            entityRef = command.entityRef,
            version = version,
            transactionDate = facts.version.transactionDate,
            prescribedPeriodRef = facts.prescribedPeriodRef,
            scopeVerdict = checkNotNull(facts.version.scopeVerdict).name,
            appliedPackVersions = facts.appliedPackVersions.map { AppliedPackVersion(it.packIdentifier, it.version) },
            traceReference = "trace/$docUuid/v$version",
            canonicalDocument = facts.version.canonical,
            aggregateId = null,
            routingFacts = routingOf(command),
        )

    private fun routingOf(command: RecordDocumentCommand): RoutingFactsData =
        RoutingFactsData(
            direction = RoutingDirection.valueOf(checkNotNull(command.direction) { "direction is absent" }.name),
            networkPath = command.networkPath,
        )
}
