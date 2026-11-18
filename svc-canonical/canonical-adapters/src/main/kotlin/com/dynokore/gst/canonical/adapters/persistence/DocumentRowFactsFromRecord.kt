package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.application.document.DocumentRowFacts
import com.dynokore.gst.canonical.application.document.DocumentRowFactsSource
import com.dynokore.gst.canonical.application.document.PeriodSpans
import com.dynokore.gst.canonical.application.document.RecordDocumentCommand
import com.dynokore.gst.canonical.application.document.ScopeAnswerCapture
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * An owned fact of the document row is not known when the row is written. Never caught by the facts source: it rolls
 * the record transaction back, so nothing is stored and the inbox retries the record (fail closed, no invented value).
 */
class RowFactUnknown(
    val fact: String,
) : RuntimeException("document row fact unknown: $fact")

private const val ISSUER_KEY_MAX = 64

/**
 * Derives the [DocumentRowFacts] of one record from the stored result text, the captured scope answer and the
 * owners' lookups (ARC-03: lookups are functions, so the cases need no database). It never writes a version: the row
 * is written by the row writer. The specification version is left null (payload-svc decides the family per
 * connection); `createdBy` is the acting user ID, never a role (INV-Q-10647).
 */
@Suppress("LongParameterList") // one lookup per owner of a fact
class DocumentRowFactsFromRecord(
    private val entityIdOf: (UUID, String) -> UUID?,
    private val spansOf: PeriodSpans,
    private val periodIdOf: (UUID, String, String) -> UUID?,
    private val partyRefOf: (UUID) -> String?,
    private val capture: ScopeAnswerCapture,
    private val writer: CanonicalDocumentWriter,
    private val actingUser: () -> String,
) : DocumentRowFactsSource {
    private val mapper = JsonMapper.builder().build()

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    override fun factsOf(
        command: RecordDocumentCommand,
        docUuid: UUID,
        version: Int,
        result: String,
    ): DocumentRowFacts? {
        val tree = mapper.readTree(result)
        val held = tree.path("reasons").size() > 0
        val state = if (held) DocumentState.HELD else DocumentState.INGESTED
        val verdict = capture.take(command)
        if (verdict == null) {
            if (held) return null
            throw RowFactUnknown("scopeAnswer")
        }
        val entityId = entityIdOf(command.tenantId, command.entityRef) ?: throw RowFactUnknown("entityId")
        val scopeVerdict =
            if (held) {
                null
            } else {
                ScopeVerdict.entries.firstOrNull { it.name == verdict.scopeVerdict }
                    ?: throw RowFactUnknown("scopeVerdict")
            }
        val typeText = verdict.documentTypeCode
        val typeCode = typeText?.toShortOrNull() ?: throw RowFactUnknown("documentTypeCode")
        val transactionDate =
            tree.path("transactionDate").asString(null)?.let { LocalDate.parse(it) }
                ?: throw RowFactUnknown("transactionDate")
        val periodRef = tree.path("prescribedPeriodRef").asString("")
        val periodId = if (periodRef.isEmpty()) null else periodIdOf(command.tenantId, command.entityRef, periodRef)
        val fiscalYear = fiscalYearOf(command, periodRef, transactionDate)
        val issuerPartyKey = issuerOf(command, docUuid)
        val content = command.content ?: throw RowFactUnknown("canonical")
        val canonical =
            try {
                writer.write(content, typeText, verdict.lineTax)
            } catch (e: IncompleteCanonicalDocument) {
                throw RowFactUnknown("canonical").apply { initCause(e) }
            }
        return DocumentRowFacts(
            entityId = entityId,
            businessUnitId = null,
            documentTypeCode = typeCode,
            issuerPartyKey = issuerPartyKey,
            fiscalYear = fiscalYear,
            internalReference = null,
            prescribedPeriodId = periodId,
            state = state,
            prescribedPeriodRef = periodRef,
            appliedPackVersions = verdict.appliedPacks,
            version =
                NewVersion(
                    canonical = canonical,
                    scopeVerdict = scopeVerdict,
                    scopePackVersion = verdict.scopePackVersion,
                    mappingPackVersion = verdict.mappingPackVersion,
                    mdePackVersion = verdict.mdePackVersion,
                    specificationVersion = null,
                    transactionDate = transactionDate,
                    createdBy = actingUser(),
                ),
        )
    }

    /** The calendar year of the span end of the period; the spans carry no fiscal-year attribute (Inference). */
    private fun fiscalYearOf(
        command: RecordDocumentCommand,
        periodRef: String,
        transactionDate: LocalDate,
    ): Short {
        if (periodRef.isEmpty()) return transactionDate.year.toShort()
        val span =
            spansOf.spansOf(command.tenantId, command.entityRef).firstOrNull { it.periodRef == periodRef }
                ?: throw RowFactUnknown("fiscalYear")
        return span.end.year.toShort()
    }

    private fun issuerOf(
        command: RecordDocumentCommand,
        docUuid: UUID,
    ): String {
        val key =
            when (command.direction) {
                Direction.SALE -> "${command.supplier.scheme}:${command.supplier.value}"
                Direction.PURCHASE -> partyRefOf(docUuid) ?: throw RowFactUnknown("issuerPartyKey")
                null -> throw RowFactUnknown("direction")
            }
        if (key.length > ISSUER_KEY_MAX) throw RowFactUnknown("issuerPartyKey")
        return key
    }
}
