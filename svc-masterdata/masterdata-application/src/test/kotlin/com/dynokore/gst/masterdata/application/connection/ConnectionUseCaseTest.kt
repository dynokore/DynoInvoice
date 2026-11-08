package com.dynokore.gst.masterdata.application.connection

import com.dynokore.gst.masterdata.application.rate.EnterRate
import com.dynokore.gst.masterdata.application.rate.EnterRateCommand
import com.dynokore.gst.masterdata.application.rate.EnterRateOutcome
import com.dynokore.gst.masterdata.application.rate.ExchangeRateEnterPermission
import com.dynokore.gst.masterdata.application.rate.RateStore
import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionTestResult
import com.dynokore.gst.masterdata.domain.connection.TestClass
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.gst.masterdata.domain.rate.Provenance
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

private object Tx : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

private class Store : ConnectionStore {
    val all = linkedMapOf<String, ConnectionRecord>()

    override fun find(connectionRef: String) = all[connectionRef]

    override fun findByServiceAndTag(
        externalService: String,
        environmentTag: String,
    ) = all.values.firstOrNull { it.externalService == externalService && it.environmentTag == environmentTag }

    override fun save(record: ConnectionRecord) {
        all[record.connectionRef] = record
    }

    override fun nextRef() = "CONN-${all.size + 1}"
}

private class Rates : RateStore {
    val all = mutableListOf<ExchangeRate>()

    private fun key(
        r: ExchangeRate,
        s: String,
        b: String,
        q: String,
        d: LocalDate,
    ) = r.source == s && r.baseCurrency == b && r.quoteCurrency == q && r.date == d

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
    ) = all.filter { key(it, source, baseCurrency, quoteCurrency, date) }

    override fun save(rate: ExchangeRate) {
        all += rate
    }
}

class ConnectionUseCaseTest {
    private val caller = PersonRef("USR-1")
    private val store = Store()
    private val rates = Rates()
    private var calls = 0
    private var testResult = ConnectionTestResult(TestClass.SUCCESS)

    private fun create(
        secret: String = "vault://kv/gst/peppol-sml",
        tag: String = "PRODUCTION",
        allow: Boolean = true,
    ) = CreateConnection(Tx, store, { allow }).handle(
        CreateConnectionCommand(caller, "CHECK_GST_REGISTER", "https://api.example.sg", secret, tag, "masterdata-svc"),
    )

    private fun test(
        ref: String,
        env: String,
        allow: Boolean = true,
    ) = TestConnection(Tx, store, {
        calls++
        testResult
    }, { allow })
        .handle(TestConnectionCommand(caller, ref, env))

    private fun enter(
        evidence: String?,
        value: String = "1.35",
        allow: Boolean = true,
    ) = EnterRate(Tx, rates, ExchangeRateEnterPermission { allow }).handle(
        EnterRateCommand(caller, "MAS", "USD", "SGD", LocalDate.parse("2026-11-03"), BigDecimal(value), evidence),
    )

    @Test
    fun `INV-AC-18780 a secret-looking credential reference keeps only the reference text`() {
        val stored = create(secret = "EXAMPLE_SECRET_REFERENCE") as CreateConnectionOutcome.Stored
        assertEquals("EXAMPLE_SECRET_REFERENCE", stored.record.credentialRef)
        assertEquals(1, store.all.size)
    }

    @Test
    fun `INV-AC-18780 a workspace testing a PRODUCTION record is refused with 0 adapter calls`() {
        val ref = (create() as CreateConnectionOutcome.Stored).record.connectionRef
        assertEquals(TestConnectionOutcome.ProductionCredentialRefused, test(ref, "WORKSPACE"))
        assertEquals(0, calls)
        assertEquals(1, store.all.size)
        val ok = test(ref, "PRODUCTION") as TestConnectionOutcome.Tested
        assertEquals(TestClass.SUCCESS, ok.result.resultClass)
        assertEquals(1, calls)
    }

    @Test
    fun `INV-AC-18780 a rate without evidence stores 0 records and with evidence stores 1 manual record`() {
        for (blank in listOf(null, "", "   ")) {
            assertEquals(EnterRateOutcome.EvidenceRequired, enter(blank))
        }
        assertEquals(0, rates.all.size)
        val stored = enter("EV-1") as EnterRateOutcome.Stored
        assertEquals(1, rates.all.size)
        assertEquals(Provenance.MANUAL_ENTRY, stored.rate.provenance)
        assertEquals("EV-1", stored.rate.evidenceRef)
    }

    @Test
    fun `a repeat of source currency and date supersedes and keeps the earlier record readable`() {
        enter("EV-1", "1.35")
        val second = enter("EV-2", "1.36") as EnterRateOutcome.Stored
        assertEquals(2, second.rate.revision)
        val history = rates.history("MAS", "USD", "SGD", LocalDate.parse("2026-11-03"))
        assertEquals(listOf("1.35", "1.36"), history.map { it.rate.toPlainString() })
    }

    @Test
    fun `a repeat of service and environment returns the existing record`() {
        val first = create() as CreateConnectionOutcome.Stored
        val second = create(secret = "vault://other") as CreateConnectionOutcome.Stored
        assertTrue(first.created)
        assertEquals(false, second.created)
        assertEquals(first.record, second.record)
        assertEquals(1, store.all.size)
        val got = GetConnection(Tx, store, { true }).handle(caller, first.record.connectionRef)
        assertEquals(GetConnectionOutcome.Found(first.record), got)
    }

    @Test
    fun `the test returns the adapter class and a failing port is classified without raw text`() {
        val ref = (create(tag = "WORKSPACE") as CreateConnectionOutcome.Stored).record.connectionRef
        testResult = ConnectionTestResult(TestClass.AUTHENTICATION_REFUSED, "HTTP_401")
        val refused = test(ref, "WORKSPACE") as TestConnectionOutcome.Tested
        assertEquals(TestClass.AUTHENTICATION_REFUSED, refused.result.resultClass)
        assertEquals("HTTP_401", refused.result.detail)
        val boom =
            TestConnection(Tx, store, { error("connect failed to 10.0.0.1") }, { true })
                .handle(TestConnectionCommand(caller, ref, "WORKSPACE")) as TestConnectionOutcome.Tested
        assertEquals(ConnectionTestResult(TestClass.UNREACHABLE), boom.result)
    }

    @Test
    fun `a denied permission wins and nothing is stored, called or read`() {
        assertEquals(CreateConnectionOutcome.Forbidden, create(allow = false))
        assertEquals(0, store.all.size)
        val ref = (create() as CreateConnectionOutcome.Stored).record.connectionRef
        assertEquals(GetConnectionOutcome.Forbidden, GetConnection(Tx, store, { false }).handle(caller, ref))
        assertEquals(TestConnectionOutcome.Forbidden, test(ref, "PRODUCTION", allow = false))
        assertEquals(0, calls)
        assertEquals(EnterRateOutcome.Forbidden, enter("EV-1", allow = false))
        assertEquals(0, rates.all.size)
        assertInstanceOf(
            GetConnectionOutcome.Unknown::class.java,
            GetConnection(Tx, store, { true }).handle(caller, "NO"),
        )
    }
}
