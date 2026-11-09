package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** In-memory [RateImportStore] for the rate import tests. */
class FakeRateImportStore : RateImportStore {
    val records = linkedMapOf<String, RateImportRecord>()

    override fun insertRunning(
        importId: String,
        entity: String,
        actor: String,
        source: String,
        rangeFrom: LocalDate,
        rangeTo: LocalDate,
    ) = RateImportRecord(
        importId,
        entity,
        actor,
        source,
        rangeFrom,
        rangeTo,
        RecordState.RUNNING,
        Instant.parse("2026-11-09T00:00:00Z"),
        null,
        null,
        null,
        null,
        null,
    ).also { records[importId] = it }

    override fun finish(
        importId: String,
        completedAt: Instant,
        appliedCount: Int,
        failedCount: Int,
        rejectedRows: List<RejectedRateRow>,
    ) {
        records[importId] =
            records.getValue(importId).copy(
                state = RecordState.SUCCEEDED,
                completedAt = completedAt,
                appliedCount = appliedCount,
                failedCount = failedCount,
                rejectedRows = rejectedRows,
            )
    }

    override fun fail(importId: String) {
        records[importId] = records.getValue(importId).copy(state = RecordState.FAILED, error = "failed")
    }

    override fun find(importId: String) = records[importId]

    override fun failRunningBefore(before: Instant) = 0
}

private val TENANT = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

private val tenants =
    object : TenantContext {
        override fun current() = TENANT

        override fun currentActor() = "IDN-0001"

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ) = block()
    }

/** A [LongRunner] whose work finishes within the wait. */
fun directRunner(): LongRunner =
    LongRunner(
        tenants,
        Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } },
        Duration.ofSeconds(10),
    )

class RateImportRecordTest {
    private val day = LocalDate.parse("2026-11-03")
    private val to = LocalDate.parse("2026-11-05")
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
    private val saved = mutableListOf<ExchangeRate>()
    private var failSave = false
    private val rates =
        object : RateStore {
            override fun latest(
                source: String,
                baseCurrency: String,
                quoteCurrency: String,
                date: LocalDate,
            ) = null

            override fun history(
                source: String,
                baseCurrency: String,
                quoteCurrency: String,
                date: LocalDate,
            ) = emptyList<ExchangeRate>()

            override fun inForce(
                source: String,
                baseCurrency: String,
                quoteCurrency: String,
                onDate: LocalDate,
            ) = null

            override fun save(rate: ExchangeRate): SaveResult {
                check(!failSave) { "store down" }
                saved += rate
                return SaveResult.Saved
            }
        }
    private val electedSource: String? = "MAS"
    private var approvedOk = true
    private val events = mutableListOf<ReferenceRateLoaded>()
    private val imports = FakeRateImportStore()
    private val clock = Clock.fixed(Instant.parse("2026-11-09T01:00:00Z"), ZoneOffset.UTC)
    private val elected = ElectedRateSource { _, _ -> electedSource?.let { ElectedRate(it, "SPOT") } }
    private val approved = ApprovedRateSources { _, _, _ -> approvedOk }

    private fun importer(runner: LongRunner = directRunner()) =
        ImportRates(tx, rates, elected, approved, { events += it }, imports, clock, runner)

    private fun row(
        n: Int,
        base: String,
        rate: String,
    ) = RateRow(n, base, "SGD", day, rate)

    private fun cmd(
        rows: List<RateRow>,
        count: Int = rows.size,
        source: String = "MAS",
    ) = ImportRatesCommand("E1", "IDN-0001", source, day, to, "IMP-1", "hash", count, rows)

    private val threeRows = listOf(row(1, "USD", "1.35"), row(2, "EUR", "abc"), row(3, "GBP", "0.9"))

    @Test
    fun `INV-AC-19374 a load with one invalid rate records applied 2 failed 1 and the rejected row`() {
        val out = importer().handle(cmd(threeRows)) as ImportRatesOutcome.Loaded
        val rec = imports.records.values.single()
        assertEquals("IMP-1", rec.importId)
        assertEquals(RecordState.SUCCEEDED, rec.state)
        assertEquals(2, rec.appliedCount)
        assertEquals(1, rec.failedCount)
        assertEquals(listOf(RejectedRateRow(2, RateRowRejection.RATE_INVALID, "rate")), rec.rejectedRows)
        assertEquals(rec, out.record)
        assertEquals(2, out.stored)
    }

    @Test
    fun `INV-AC-19374 a load that stores 0 records is SUCCEEDED with applied 0 and no event`() {
        importer().handle(cmd(listOf(row(1, "USD", "abc"), row(2, "EUR", "-1"))))
        val rec = imports.records.values.single()
        assertEquals(RecordState.SUCCEEDED, rec.state)
        assertEquals(0, rec.appliedCount)
        assertEquals(2, rec.failedCount)
        assertEquals(0, events.size)
    }

    @Test
    fun `INV-AC-19374 a refusal as SourceNotElected SourceNotApproved or RowCountMismatch stores 0 records`() {
        assertEquals(ImportRatesOutcome.SourceNotElected, importer().handle(cmd(threeRows, source = "MAS ")))
        assertEquals(0, imports.records.size)
        approvedOk = false
        assertEquals(ImportRatesOutcome.SourceNotApproved, importer().handle(cmd(threeRows)))
        assertEquals(0, imports.records.size)
        approvedOk = true
        assertEquals(ImportRatesOutcome.RowCountMismatch, importer().handle(cmd(threeRows, 4)))
        assertEquals(0, imports.records.size)
        assertEquals(0, saved.size + events.size)
    }

    @Test
    fun `INV-AC-19374 the store holds the actor and the range of the command`() {
        importer().handle(cmd(threeRows))
        val rec = imports.records.getValue("IMP-1")
        assertEquals("IDN-0001", rec.actor)
        assertEquals("E1", rec.entity)
        assertEquals("MAS", rec.source)
        assertEquals(day, rec.rangeFrom)
        assertEquals(to, rec.rangeTo)
    }

    @Test
    fun `INV-AC-19374 a work that throws leaves the record FAILED, rethrows and publishes no event`() {
        failSave = true
        val e = assertThrows(IllegalStateException::class.java) { importer().handle(cmd(threeRows)) }
        assertEquals("store down", e.message)
        assertEquals(RecordState.FAILED, imports.records.getValue("IMP-1").state)
        assertEquals(0, events.size)
    }

    @Test
    fun `INV-AC-19374 a work that outlasts the wait gives Running with the RUNNING record`() {
        val gate = CountDownLatch(1)
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }
        val slow = LongRunner(tenants, executor, Duration.ofMillis(50))
        val blocking =
            ImportRates(
                tx,
                rates,
                elected,
                approved,
                { events += it },
                object : RateImportStore by imports {
                    override fun finish(
                        importId: String,
                        completedAt: Instant,
                        appliedCount: Int,
                        failedCount: Int,
                        rejectedRows: List<RejectedRateRow>,
                    ) {
                        gate.await()
                        imports.finish(importId, completedAt, appliedCount, failedCount, rejectedRows)
                    }
                },
                clock,
                slow,
            )
        val out = assertInstanceOf(ImportRatesOutcome.Running::class.java, blocking.handle(cmd(threeRows)))
        assertEquals(RecordState.RUNNING, out.record.state)
        assertEquals("IMP-1", out.record.importId)
        gate.countDown()
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `INV-AC-19374 ReadRateImportRecord gives the record to its actor and null to another`() {
        importer().handle(cmd(threeRows))
        val read = ReadRateImportRecord(tx, imports)
        assertSame(imports.records.getValue("IMP-1"), read.handle("IMP-1", "IDN-0001"))
        assertNull(read.handle("IMP-1", "IDN-0001 "))
        assertNull(read.handle("IMP-1", "idn-0001"))
        assertNull(read.handle("IMP-2", "IDN-0001"))
    }

    @Test
    fun `INV-AC-19374 one event per load that stored at least one record`() {
        importer().handle(cmd(threeRows))
        assertEquals(1, events.size)
        assertEquals(setOf("GBP/SGD", "USD/SGD"), events.single().currencyPairs)
        assertEquals("IMP-1", events.single().importId)
    }
}
