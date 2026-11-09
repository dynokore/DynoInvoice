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
    private val elected = ElectedRateSource { _, _ -> electedSource?.let { ElectedRate(it, "SPOT") } }
    private val approved = ApprovedRateSources { s, b, _ -> b == "SPOT" && s in approvedSources }
    private val importer = ImportRates(tx, store, elected, approved) { events += it }

    private fun cmd(
        source: String,
        rows: List<RateRow> = listOf(row(1, "USD", "1.35")),
        count: Int = rows.size,
    ) = ImportRatesCommand("E1", source, day, "IMP-1", "hash", count, rows)

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
    fun `rejected rows hold a closed reason, rates and currencies are probed`() {
        val rows =
            listOf(
                row(1, "usd", "1.35"),
                row(2, "EUR", "0"),
                row(3, "GBP", "abc"),
                row(4, "JPY", "0.009"),
            )
        val out = importer.handle(cmd("MAS", rows)) as ImportRatesOutcome.Loaded
        assertEquals(
            listOf(
                RateRowRejection.CURRENCY_INVALID,
                RateRowRejection.RATE_INVALID,
                RateRowRejection.RATE_INVALID,
            ),
            out.rejected.map { it.reason },
        )
        assertEquals("JPY/SGD", events.single().currencyPairs.single())
    }

    @Test
    fun `a revision conflict rejects only that row`() {
        val conflicting =
            object : RateStore by store {
                override fun save(rate: ExchangeRate) =
                    if (rate.baseCurrency == "EUR") SaveResult.RevisionConflict else store.save(rate)
            }
        val rows = listOf(row(1, "USD", "1.35"), row(2, "EUR", "1.5"))
        val out =
            ImportRates(tx, conflicting, elected, approved) { events += it }.handle(cmd("MAS", rows))
                as ImportRatesOutcome.Loaded
        assertEquals(1, out.stored)
        assertEquals(listOf(RateRowRejection.REVISION_CONFLICT), out.rejected.map { it.reason })
    }

    @Test
    fun `all rows invalid or rowCount 0 stores 0 and publishes no event`() {
        val bad = importer.handle(cmd("MAS", listOf(row(1, "USD", "abc"), row(2, "EUR", "-1"))))
        assertEquals(0, (bad as ImportRatesOutcome.Loaded).stored)
        assertEquals(0, (importer.handle(cmd("MAS", emptyList())) as ImportRatesOutcome.Loaded).stored)
        assertTrue(events.isEmpty() && all.isEmpty())
    }

    @Test
    fun `RateRowRejection has exactly the three ruled members`() {
        assertEquals(
            setOf("CURRENCY_INVALID", "RATE_INVALID", "REVISION_CONFLICT"),
            RateRowRejection.entries.map { it.name }.toSet(),
        )
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

    // INV-AC-19334: point-in-time election and approval.
    private val d10 = LocalDate.parse("2026-03-10")
    private val d20 = LocalDate.parse("2026-03-20")
    private val cut = LocalDate.parse("2026-03-15")

    private fun rangeCmd(
        source: String,
        rows: List<RateRow>,
        count: Int = rows.size,
        from: LocalDate = d10,
    ) = ImportRatesCommand("E1", source, from, "IMP-2", "hash", count, rows)

    private fun importerWith(
        el: (LocalDate) -> ElectedRate?,
        ap: (String, String, LocalDate) -> Boolean = { _, _, _ -> true },
    ) = ImportRates(tx, store, { _, d -> el(d) }, { s, b, d -> ap(s, b, d) }) { events += it }

    private fun twoRows() = listOf(row(1, "USD", "1.35", d10), row(2, "EUR", "1.5", d20))

    private fun assertNothing() {
        assertEquals(0, all.size)
        assertEquals(0, events.size)
    }

    @Test
    fun `INV-AC-19334 source elected on one row date only is SourceNotElected`() {
        val imp = importerWith({ d -> ElectedRate(if (d.isAfter(cut)) "MAS2" else "MAS", "DAILY") })
        assertEquals(ImportRatesOutcome.SourceNotElected, imp.handle(rangeCmd("MAS", twoRows())))
        assertNothing()
    }

    @Test
    fun `INV-AC-19334 no election on one of two row dates is SourceNotElected`() {
        val imp = importerWith({ d -> if (d == d20) null else ElectedRate("MAS", "DAILY") })
        assertEquals(ImportRatesOutcome.SourceNotElected, imp.handle(rangeCmd("MAS", twoRows())))
        assertNothing()
    }

    @Test
    fun `INV-AC-19334 approval false after a date gives SourceNotApproved while earlier rows load`() {
        val imp =
            importerWith({ ElectedRate("MAS", "DAILY") }, { _, _, d -> !d.isAfter(cut) })
        assertEquals(ImportRatesOutcome.SourceNotApproved, imp.handle(rangeCmd("MAS", twoRows())))
        assertNothing()
        val ok = imp.handle(rangeCmd("MAS", listOf(row(1, "USD", "1.35", d10), row(2, "EUR", "1.5", cut))))
        assertEquals(2, (ok as ImportRatesOutcome.Loaded).stored)
        assertEquals(1, events.size)
    }

    @Test
    fun `INV-AC-19334 approval receives the basis of the election of each date`() {
        val seen = mutableListOf<Triple<String, String, LocalDate>>()
        val imp =
            importerWith(
                { d -> ElectedRate("MAS", if (d == d10) "DAILY" else "MONTHLY_AVERAGE") },
                { s, b, d ->
                    seen += Triple(s, b, d)
                    true
                },
            )
        imp.handle(rangeCmd("MAS", twoRows()))
        assertEquals(
            setOf(Triple("MAS", "DAILY", d10), Triple("MAS", "MONTHLY_AVERAGE", d20)),
            seen.toSet(),
        )
        assertEquals(2, seen.size)
    }

    @Test
    fun `INV-AC-19334 no rows is checked on rangeFrom`() {
        val asked = mutableListOf<LocalDate>()
        val imp =
            importerWith({ d ->
                asked += d
                null
            })
        assertEquals(ImportRatesOutcome.SourceNotElected, imp.handle(rangeCmd("MAS", emptyList(), 0, d20)))
        assertEquals(listOf(d20), asked)
        assertNothing()
    }

    @Test
    fun `INV-AC-19334 look-alike sources are SourceNotElected`() {
        val imp = importerWith({ ElectedRate("MAS", "DAILY") })
        for (lookAlike in listOf("MAS ", "mas")) {
            assertEquals(ImportRatesOutcome.SourceNotElected, imp.handle(rangeCmd(lookAlike, twoRows())), lookAlike)
        }
        assertNothing()
    }

    @Test
    fun `INV-AC-19334 row count differing after election and approval is RowCountMismatch`() {
        val imp = importerWith({ ElectedRate("MAS", "DAILY") })
        assertEquals(ImportRatesOutcome.RowCountMismatch, imp.handle(rangeCmd("MAS", twoRows(), 3)))
        assertNothing()
    }

    @Test
    fun `INV-AC-19334 all dates pass stores the records and publishes one event`() {
        val imp = importerWith({ ElectedRate("MAS", "DAILY") })
        val out = imp.handle(rangeCmd("MAS", twoRows()))
        assertEquals(2, (out as ImportRatesOutcome.Loaded).stored)
        assertEquals(2, all.size)
        assertEquals(1, events.size)
    }
}
