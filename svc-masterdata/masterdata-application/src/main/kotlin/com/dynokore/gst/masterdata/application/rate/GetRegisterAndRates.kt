package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.application.registry.RegisterAnswer
import com.dynokore.gst.masterdata.application.registry.RegisterQuestion
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** The result of a rate lookup: the record in force, or absent. A rate is never invented. */
sealed interface RateLookup {
    data class Found(
        val rate: ExchangeRate,
    ) : RateLookup

    data object Absent : RateLookup
}

/** INV-REQ-506: the record of [source] in force on [date] for the pair, else [RateLookup.Absent]. */
class LookupRate(
    private val tx: TransactionRunner,
    private val store: RateStore,
) {
    fun find(
        source: String,
        baseCurrency: String,
        quoteCurrency: String,
        date: LocalDate,
    ): RateLookup =
        tx.inTransaction { store.inForce(source, baseCurrency, quoteCurrency, date) }?.let { RateLookup.Found(it) }
            ?: RateLookup.Absent
}

/** The register lookup of INV-T-4946 (`LookupGstRegister.handle`); an outage is `REGISTER_UNAVAILABLE`. */
fun interface RegisterLookup {
    fun lookup(question: RegisterQuestion): RegisterAnswer
}

data class RegisterAndRatesQuestion(
    val register: RegisterQuestion,
    val currency: String,
    val transactionDate: LocalDate,
)

/** [rate] is null when the currency is SGD (no rate needed); otherwise found or absent, independent of [register]. */
data class RegisterAndRates(
    val register: RegisterAnswer,
    val rate: RateLookup?,
)

/**
 * INV-REQ-506: the register facts plus, for a currency other than SGD, the rate (currency to SGD) of the elected source
 * in force on `transactionDate`. A register outage and a missing rate each degrade only their own part.
 */
class GetRegisterAndRates(
    private val register: RegisterLookup,
    private val elected: ElectedRateSource,
    private val rates: LookupRate,
) {
    fun handle(question: RegisterAndRatesQuestion): RegisterAndRates {
        val answer = register.lookup(question.register)
        if (question.currency == HOME) return RegisterAndRates(answer, null)
        val source = elected.elected(question.register.entity, question.transactionDate)
        val rate =
            source?.let { rates.find(it, question.currency, HOME, question.transactionDate) } ?: RateLookup.Absent
        return RegisterAndRates(answer, rate)
    }

    private companion object {
        const val HOME = "SGD"
    }
}
