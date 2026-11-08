package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.application.registry.REGISTER_UNAVAILABLE
import com.dynokore.gst.masterdata.application.registry.RegisterAnswer
import com.dynokore.gst.masterdata.application.registry.RegisterQuestion
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.gst.masterdata.domain.registry.Reconciliation
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ImportRatesTest {
    private val day = LocalDate.parse("2026-11-03")
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
    private val all = mutableListOf<ExchangeRate>()
    private val store =
        object : RateStore {
            override fun latest(
                source: String,
                baseCurrency: String,
                quoteCurrency: String,
                date: LocalDate,
            ) = history(source, baseCurrency, quoteCurrency, date).lastOrNull()

            override fun history(
                source: String,
                baseCurrency: String,
                quoteCurrency: String,
                date: LocalDate,
            ) = all.filter {
                it.source == source && it.baseCurrency == baseCurrency && it.quoteCurrency == quoteCurrency &&
                    it.date == date
            }

            override fun inForce(
                source: String,
                baseCurrency: String,
                quoteCurrency: String,
                onDate: LocalDate,
            ) = all
                .filter {
                    it.source == source && it.baseCurrency == baseCurrency && it.quoteCurrency == quoteCurrency &&
                        !it.date.isAfter(onDate)
                }.maxWithOrNull(compareBy({ it.date }, { it.revision }))

            override fun save(rate: ExchangeRate): SaveResult {
                all += rate
                return SaveResult.Saved
            }
        }
    private var electedSource: String? = "MAS"
    private var approvedSources = setOf("MAS")
    private val events = mutableListOf<ReferenceRateLoaded>()
    private val elected = ElectedRateSource { _, _ -> electedSource }
    private val approved = ApprovedRateSources { s, b, _ -> b == "SPOT" && s in approvedSources }
    private val importer = ImportRates(tx, store, elected, approved) { events += it }

    private fun cmd(
        source: String,
        rows: List<RateRow> = listOf(row(1, "USD", "1.35")),
        count: Int = rows.size,
    ) = ImportRatesCommand("E1", source, "SPOT", day, "IMP-1", "hash", count, rows)

    private fun row(
        n: Int,
        base: String,
        rate: String,
        date: LocalDate = day,
    ) = RateRow(n, base, "SGD", date, rate)

    @Test
    fun `INV-AC-18781 only the elected approved source stores records, others store 0`() {
        val ok = importer.handle(cmd("MAS"))
        assertEquals(1, (ok as ImportRatesOutcome.Loaded).stored)
        assertEquals(1, all.size)
        assertEquals(1, events.size)

        approvedSources = setOf("MAS", "MAS2", "mas", "MAS ")
        for (lookAlike in listOf("MAS2", "mas", "MAS ")) {
            assertEquals(ImportRatesOutcome.SourceNotElected, importer.handle(cmd(lookAlike)), lookAlike)
            assertEquals(1, all.size)
            assertEquals(1, events.size)
        }
        approvedSources = setOf("MAS2")
        assertEquals(ImportRatesOutcome.SourceNotApproved, importer.handle(cmd("MAS")))
        assertEquals(1, all.size)
        assertEquals(1, events.size)
        electedSource = "MAS2"
        approvedSources = emptySet()
        assertEquals(ImportRatesOutcome.SourceNotApproved, importer.handle(cmd("MAS2")))
        assertEquals(1, all.size)
        assertEquals(1, events.size)
    }

    @Test
    fun `INV-AC-18781 an absent rate is reported absent with the register part present`() {
        importer.handle(cmd("MAS"))
        val register = RegisterAnswer("REGISTERED", day, Reconciliation.NoChange, null)
        val get =
            GetRegisterAndRates({ register }, elected, LookupRate(tx, store))
        val q = RegisterQuestion("E1", "201912345A")
        val eur = get.handle(RegisterAndRatesQuestion(q, "EUR", day))
        assertEquals(register, eur.register)
        assertEquals(RateLookup.Absent, eur.rate)
        val usd = get.handle(RegisterAndRatesQuestion(q, "USD", day.plusDays(2)))
        assertEquals("1.35", (usd.rate as RateLookup.Found).rate.rate.toPlainString())
        assertEquals(RateLookup.Absent, get.handle(RegisterAndRatesQuestion(q, "USD", day.minusDays(1))).rate)
        assertNull(get.handle(RegisterAndRatesQuestion(q, "SGD", day)).rate)
        electedSource = null
        assertEquals(RateLookup.Absent, get.handle(RegisterAndRatesQuestion(q, "USD", day)).rate)
    }

    @Test
    fun `row count mismatch stores nothing and invalid rates are rejected while others are stored`() {
        val rows = listOf(row(1, "USD", "1.35"), row(2, "EUR", "abc"), row(3, "GBP", "-1"), row(4, "JPY", "0.009"))
        assertEquals(ImportRatesOutcome.RowCountMismatch, importer.handle(cmd("MAS", rows, 5)))
        assertTrue(all.isEmpty() && events.isEmpty())
        val out = assertInstanceOf(ImportRatesOutcome.Loaded::class.java, importer.handle(cmd("MAS", rows)))
        assertEquals(2, out.stored)
        assertEquals(listOf(2, 3), out.rejected.map { it.row })
        assertEquals(setOf("USD/SGD", "JPY/SGD"), events.single().currencyPairs)
    }

    @Test
    fun `a register outage degrades only the register part`() {
        val down = RegisterAnswer(REGISTER_UNAVAILABLE, null, Reconciliation.NoChange, null)
        val out =
            GetRegisterAndRates({ down }, elected, LookupRate(tx, store))
                .handle(RegisterAndRatesQuestion(RegisterQuestion("E1", "201912345A"), "USD", day))
        assertEquals(REGISTER_UNAVAILABLE, out.register.status)
        assertEquals(RateLookup.Absent, out.rate)
    }
}
