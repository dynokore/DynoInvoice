package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.gst.masterdata.domain.rate.Provenance
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.math.BigDecimal
import java.time.LocalDate

/** The rate source elected for an entity on a date; implemented by INV-T-18775. Null when none is elected. */
fun interface ElectedRateSource {
    fun elected(
        entity: String,
        date: LocalDate,
    ): String?
}

/** The approved rate sources for a basis on a date; implemented by INV-T-18775. */
fun interface ApprovedRateSources {
    fun isApproved(
        source: String,
        basis: String,
        date: LocalDate,
    ): Boolean
}

/** What a successful load tells the world: the source, the currency pairs and the date range (INV-REQ-506). */
data class ReferenceRateLoaded(
    val source: String,
    val currencyPairs: Set<String>,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val importId: String,
)

/** Requests the `ReferenceRateLoaded` event; the publish itself is wired by INV-T-18775. */
fun interface ReferenceRateLoadedPublisher {
    fun publish(event: ReferenceRateLoaded)
}

/** One mapped row of a rate file; [rate] is the text as mapped and is validated here. */
data class RateRow(
    val row: Int,
    val baseCurrency: String,
    val quoteCurrency: String,
    val date: LocalDate,
    val rate: String,
)

data class RejectedRateRow(
    val row: Int,
    val code: String,
    val field: String,
)

data class ImportRatesCommand(
    val entity: String,
    val source: String,
    val basis: String,
    val asOf: LocalDate,
    val importId: String,
    val fileHash: String,
    val rowCount: Int,
    val rows: List<RateRow>,
)

sealed interface ImportRatesOutcome {
    data class Loaded(
        val stored: Int,
        val rejected: List<RejectedRateRow>,
    ) : ImportRatesOutcome

    /** No source is elected, or the request source is not exactly the elected one. Nothing stored. */
    data object SourceNotElected : ImportRatesOutcome

    /** The source is not approved for the basis on the date. Nothing stored. */
    data object SourceNotApproved : ImportRatesOutcome

    /** The mapped rows do not number `rowCount`. Nothing stored. */
    data object RowCountMismatch : ImportRatesOutcome
}

/**
 * INV-REQ-506: file import of rates. The source must equal (exact string equality) the elected source and be approved
 * for the basis on the date, else the request is refused and stores 0. A row count different from `rowCount` is
 * refused. A row with an invalid rate (or currency) is listed as rejected and the others are stored (the rule of
 * the counterparty import). One `ReferenceRateLoaded` is requested per load that stored records, after the commit.
 */
class ImportRates(
    private val tx: TransactionRunner,
    private val store: RateStore,
    private val elected: ElectedRateSource,
    private val approved: ApprovedRateSources,
    private val publisher: ReferenceRateLoadedPublisher,
) {
    fun handle(command: ImportRatesCommand): ImportRatesOutcome {
        val outcome =
            if (elected.elected(command.entity, command.asOf) != command.source) {
                ImportRatesOutcome.SourceNotElected
            } else if (!approved.isApproved(command.source, command.basis, command.asOf)) {
                ImportRatesOutcome.SourceNotApproved
            } else if (command.rows.size != command.rowCount) {
                ImportRatesOutcome.RowCountMismatch
            } else {
                null
            }
        if (outcome != null) return outcome
        val (stored, rejected) = tx.inTransaction { load(command) }
        if (stored.isNotEmpty()) {
            publisher.publish(
                ReferenceRateLoaded(
                    command.source,
                    stored.map { "${it.baseCurrency}/${it.quoteCurrency}" }.toSortedSet(),
                    stored.minOf { it.date },
                    stored.maxOf { it.date },
                    command.importId,
                ),
            )
        }
        return ImportRatesOutcome.Loaded(stored.size, rejected)
    }

    private fun load(command: ImportRatesCommand): Pair<List<ExchangeRate>, List<RejectedRateRow>> {
        val stored = mutableListOf<ExchangeRate>()
        val rejected = mutableListOf<RejectedRateRow>()
        for (row in command.rows) {
            val rate = row.rate.trim().toBigDecimalOrNull()
            if (!CURRENCY.matches(row.baseCurrency)) {
                rejected += RejectedRateRow(row.row, "CURRENCY_INVALID", "baseCurrency")
            } else if (!CURRENCY.matches(row.quoteCurrency)) {
                rejected += RejectedRateRow(row.row, "CURRENCY_INVALID", "quoteCurrency")
            } else if (rate == null || rate.signum() <= 0) {
                rejected += RejectedRateRow(row.row, "RATE_INVALID", "rate")
            } else {
                val record = record(command, row, rate)
                if (store.save(record) == SaveResult.Saved) {
                    stored += record
                } else {
                    rejected += RejectedRateRow(row.row, "REVISION_CONFLICT", "rate")
                }
            }
        }
        return stored to rejected
    }

    private fun record(
        command: ImportRatesCommand,
        row: RateRow,
        rate: BigDecimal,
    ): ExchangeRate {
        val previous = store.latest(command.source, row.baseCurrency, row.quoteCurrency, row.date)
        return ExchangeRate(
            source = command.source,
            baseCurrency = row.baseCurrency,
            quoteCurrency = row.quoteCurrency,
            date = row.date,
            rate = rate,
            provenance = Provenance.IMPORT,
            evidenceRef = null,
            revision = (previous?.revision ?: 0) + 1,
            importId = command.importId,
        )
    }

    private companion object {
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}
