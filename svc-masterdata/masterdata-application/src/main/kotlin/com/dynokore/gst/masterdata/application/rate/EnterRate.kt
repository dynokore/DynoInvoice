package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.gst.masterdata.domain.rate.Provenance
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.math.BigDecimal
import java.time.LocalDate

/** The rate records; a superseded revision stays readable. */
interface RateStore {
    /** The newest revision for (source, pair, date), or null. */
    fun latest(
        source: String,
        baseCurrency: String,
        quoteCurrency: String,
        date: LocalDate,
    ): ExchangeRate?

    /** Every revision for (source, pair, date), oldest first. */
    fun history(
        source: String,
        baseCurrency: String,
        quoteCurrency: String,
        date: LocalDate,
    ): List<ExchangeRate>

    fun save(rate: ExchangeRate)
}

/** The scoped permission `exchange-rate.enter`, decided per request (INV-Q-10027). No role name crosses this port. */
fun interface ExchangeRateEnterPermission {
    fun allows(caller: PersonRef): Boolean
}

data class EnterRateCommand(
    val caller: PersonRef,
    val source: String,
    val baseCurrency: String,
    val quoteCurrency: String,
    val date: LocalDate,
    val rate: BigDecimal,
    val evidenceRef: String?,
)

sealed interface EnterRateOutcome {
    data class Stored(
        val rate: ExchangeRate,
    ) : EnterRateOutcome

    /** No non-blank evidence reference; nothing is stored. */
    data object EvidenceRequired : EnterRateOutcome

    data object Forbidden : EnterRateOutcome
}

/**
 * INV-REQ-506: manual entry with evidence. A repeat of (source, pair, date) stores a new revision and keeps the
 * earlier record. The permission is asked first.
 */
class EnterRate(
    private val tx: TransactionRunner,
    private val store: RateStore,
    private val permission: ExchangeRateEnterPermission,
) {
    fun handle(command: EnterRateCommand): EnterRateOutcome =
        tx.inTransaction {
            val evidence = command.evidenceRef
            if (!permission.allows(command.caller)) {
                EnterRateOutcome.Forbidden
            } else if (evidence == null || evidence.isBlank()) {
                EnterRateOutcome.EvidenceRequired
            } else {
                val previous = store.latest(command.source, command.baseCurrency, command.quoteCurrency, command.date)
                val rate =
                    ExchangeRate(
                        source = command.source,
                        baseCurrency = command.baseCurrency,
                        quoteCurrency = command.quoteCurrency,
                        date = command.date,
                        rate = command.rate,
                        provenance = Provenance.MANUAL_ENTRY,
                        evidenceRef = evidence,
                        revision = (previous?.revision ?: 0) + 1,
                    )
                store.save(rate)
                EnterRateOutcome.Stored(rate)
            }
        }
}
