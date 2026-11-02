package com.dynokore.gst.submission.domain.listing

import java.math.BigDecimal
import java.time.Instant

/**
 * The header block of an export (INV-REQ-978). [filter] is `SubmissionFilter.canonical()` of the caller and may be
 * empty; [generatedBy] is a user ID. Neither a clock nor an identity is read here: both are arguments.
 */
data class ExportHeader(
    val entityRef: String,
    val gstn: String,
    val periodRef: String,
    val generatedAt: Instant,
    val generatedBy: String,
    val filter: String,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(gstn.isNotBlank()) { "gstn must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(generatedBy.isNotBlank()) { "generatedBy must not be blank" }
    }
}

/** The format-neutral export table: header, column names, text cells, and the row count and totals of the body. */
data class ExportTable(
    val header: ExportHeader,
    val columns: List<String>,
    val cells: List<List<String>>,
    val rowCount: Int,
    val totalTaxableAmount: BigDecimal,
    val totalGstAmount: BigDecimal,
)

object SubmissionExport {
    val COLUMNS: List<String> =
        listOf(
            "docUuid",
            "version",
            "documentNumber",
            "documentTypeCode",
            "direction",
            "submissionType",
            "counterpartyName",
            "counterpartyUen",
            "transactionDate",
            "taxableAmount",
            "gstAmount",
            "submittedAt",
            "acknowledgedAt",
            "acknowledgementId",
            "state",
            "superseded",
        )

    private val TEXT_COLUMNS = setOf("documentNumber", "counterpartyName", "counterpartyUen", "acknowledgementId")
    private val TEXT_INDEXES: Set<Int> = TEXT_COLUMNS.map { COLUMNS.indexOf(it) }.toSet()
    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')

    private val QUOTE_TRIGGERS = setOf(',', '"', '\r', '\n')

    private val HEADER_NAMES =
        listOf(
            "entityRef",
            "gstn",
            "periodRef",
            "generatedAt",
            "generatedBy",
            "filter",
            "rowCount",
            "totalTaxableAmount",
            "totalGstAmount",
        )

    /** One cell list per input row in input order; row count and totals come from [SubmissionTotals.of]. */
    fun table(
        header: ExportHeader,
        rows: List<SubmissionRow>,
    ): ExportTable {
        val totals = SubmissionTotals.of(rows)
        return ExportTable(
            header = header,
            columns = COLUMNS,
            cells = rows.map(::cellsOf),
            rowCount = totals.rowCount,
            totalTaxableAmount = totals.totalTaxableAmount,
            totalGstAmount = totals.totalGstAmount,
        )
    }

    /** RFC 4180 CSV (CRLF) with the header block, an empty line, the column line and one line per row. */
    fun csv(table: ExportTable): String {
        val h = table.header
        val headerValues =
            listOf(
                safeText(h.entityRef),
                safeText(h.gstn),
                safeText(h.periodRef),
                h.generatedAt.toString(),
                safeText(h.generatedBy),
                safeText(h.filter),
                table.rowCount.toString(),
                table.totalTaxableAmount.toPlainString(),
                table.totalGstAmount.toPlainString(),
            )
        val sb = StringBuilder()
        sb.append(line(HEADER_NAMES)).append(CRLF)
        sb.append(line(headerValues)).append(CRLF)
        sb.append(CRLF)
        sb.append(line(table.columns)).append(CRLF)
        for (row in table.cells) {
            val fields = row.mapIndexed { i, v -> if (i in TEXT_INDEXES) safeText(v) else v }
            sb.append(line(fields)).append(CRLF)
        }
        return sb.toString()
    }

    private fun cellsOf(r: SubmissionRow): List<String> =
        listOf(
            r.docUuid,
            r.version.toString(),
            r.documentNumber,
            r.documentTypeCode,
            r.direction.name,
            r.submissionType.name,
            r.counterpartyName,
            r.counterpartyUen,
            r.transactionDate.toString(),
            r.taxableAmount.setScale(2).toPlainString(),
            r.gstAmount.setScale(2).toPlainString(),
            r.submittedAt?.toString().orEmpty(),
            r.acknowledgedAt?.toString().orEmpty(),
            r.acknowledgementId.orEmpty(),
            r.state.name,
            r.superseded.toString(),
        )

    /** OWASP CSV injection (CWE-1236): a leading apostrophe on a text that starts with a formula character. */
    private fun safeText(value: String): String =
        if (value.isNotEmpty() &&
            value[0] in FORMULA_STARTS
        ) {
            "'$value"
        } else {
            value
        }

    private fun line(fields: List<String>): String = fields.joinToString(",") { quote(it) }

    private fun quote(field: String): String =
        if (field.any { it in QUOTE_TRIGGERS }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private const val CRLF = "\r\n"
}
