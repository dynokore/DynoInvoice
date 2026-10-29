package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.coding.LineCoder
import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.coding.LineInput
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.tenancykernel.TenantId
import java.math.BigDecimal
import java.math.RoundingMode

/** One side's document to code. [date] is the one resolution date: the caller passes the preceding date for a credit note. */
data class CodeDocumentCommand(
    val tenant: TenantId?,
    val date: TransactionDate,
    val lines: List<DocumentLine>,
)

data class DocumentLine(
    val lineId: String,
    /** The line amount without GST. */
    val amount: BigDecimal,
    val input: LineInput,
)

data class CodedDocumentLine(
    val lineId: String,
    val amount: BigDecimal,
    val categoryCode: String,
    /** The rate in percent, resolved for the document date. */
    val rate: BigDecimal,
)

/** One IBT-118 group: one per distinct category code (INV-REQ-363). */
data class Subtotal(
    val categoryCode: String,
    val rate: BigDecimal,
    val taxable: BigDecimal,
    val tax: BigDecimal,
)

data class CodedDocument(
    val lines: List<CodedDocumentLine>,
    val subtotals: List<Subtotal>,
    val totalWithoutGst: BigDecimal,
    val totalGst: BigDecimal,
    val totalWithGst: BigDecimal,
)

sealed interface DocumentCodingResult {
    data class Coded(
        val document: CodedDocument,
    ) : DocumentCodingResult

    /** No version of the pack is effective on the date: RUL-002 (INV-REQ-362), nothing coded against another version. */
    data class Refused(
        val code: ErrorCode,
        val packType: PackType,
        val date: TransactionDate,
    ) : DocumentCodingResult

    /** Lines the coding refused, by line id; no subtotal is built from a document with a refused line. */
    data class LinesRefused(
        val refusals: Map<String, ErrorCode>,
    ) : DocumentCodingResult
}

/** A rate the pack data does not settle; the evaluation stops, no default rate is applied (INV-Q-11616). */
class DocumentCodingFailure(
    message: String,
) : IllegalStateException(message)

/**
 * Codes one side of a document under the packs of the resolution date and builds its subtotals (INV-REQ-363). The two
 * sides of a transaction are coded by separate calls and nothing here compares them (INV-REQ-365).
 */
class DocumentCoder(
    private val mappings: MappingEntriesPort,
    private val catalogues: CodeCataloguePort,
    private val scenarios: ScenarioDataPort,
) {
    fun code(command: CodeDocumentCommand): DocumentCodingResult {
        val date = command.date
        val mapping =
            mappings.mappingAt(command.tenant, date) ?: return refused(PackTypes.MAPPING, date)
        val catalogue =
            catalogues.catalogueAt(command.tenant, date) ?: return refused(PackTypes.CODE_CATALOGUE, date)
        val scenario =
            scenarios.scenarioAt(command.tenant, date) ?: return refused(PackTypes.SCENARIO, date)
        val coder = LineCoder(mapping, catalogue, scenario)

        val codings = command.lines.map { it to coder.code(it.input) }
        val refusals =
            codings
                .mapNotNull { (line, coding) -> (coding as? LineCoding.Refused)?.let { line.lineId to it.code } }
                .toMap()
        if (refusals.isNotEmpty()) return DocumentCodingResult.LinesRefused(refusals)

        val coded =
            codings.map { (line, coding) ->
                val c = coding as LineCoding.Coded
                CodedDocumentLine(line.lineId, line.amount, c.categoryCode, rateOf(c, catalogue))
            }
        return DocumentCodingResult.Coded(build(coded))
    }

    private fun refused(
        packType: PackType,
        date: TransactionDate,
    ) = DocumentCodingResult.Refused(ErrorCode.RUL_002, packType, date)

    private fun rateOf(
        coded: LineCoding.Coded,
        catalogue: CodeCatalogue,
    ): BigDecimal {
        val fixed = coded.rate
        if (fixed != null &&
            !coded.prevailingRate
        ) {
            return numeric(fixed, "rate of the treatment for ${coded.categoryCode}")
        }
        val entry =
            catalogue.entryOf(coded.categoryCode)
                ?: throw DocumentCodingFailure("code ${coded.categoryCode} is not in the catalogue")
        if (entry.annexERate != NOT_APPLICABLE) return numeric(entry.annexERate, "annexERate of ${entry.code}")
        val submission =
            entry.submissionRate
                ?: throw DocumentCodingFailure("annexERate of ${entry.code} is NA and has no submissionRate")
        return numeric(submission, "submissionRate of ${entry.code}")
    }

    private fun numeric(
        text: String,
        what: String,
    ): BigDecimal = text.toBigDecimalOrNull() ?: throw DocumentCodingFailure("$what is not numeric: '$text'")

    private fun build(lines: List<CodedDocumentLine>): CodedDocument {
        val subtotals =
            lines
                .groupBy { it.categoryCode }
                .toSortedMap()
                .map { (code, group) ->
                    val rates = group.map { it.rate.stripTrailingZeros() }.distinct()
                    if (rates.size != 1) throw DocumentCodingFailure("lines of $code carry different rates")
                    val rate = group.first().rate
                    val taxable = group.fold(BigDecimal.ZERO) { sum, l -> sum + l.amount }
                    val tax = taxable.multiply(rate).divide(HUNDRED).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    Subtotal(code, rate, taxable, tax)
                }
        val net = subtotals.fold(BigDecimal.ZERO) { sum, s -> sum + s.taxable }
        val gst = subtotals.fold(BigDecimal.ZERO) { sum, s -> sum + s.tax }
        return CodedDocument(lines, subtotals, net, gst, net + gst)
    }

    private companion object {
        const val NOT_APPLICABLE = "NA"
        const val MONEY_SCALE = 2
        val HUNDRED: BigDecimal = BigDecimal(100)
    }
}
