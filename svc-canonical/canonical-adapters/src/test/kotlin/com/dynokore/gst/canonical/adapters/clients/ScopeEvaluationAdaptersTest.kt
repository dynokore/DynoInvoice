package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.application.document.CounterpartyLookup
import com.dynokore.gst.canonical.application.document.LineAnswer
import com.dynokore.gst.canonical.application.document.LineScopeFacts
import com.dynokore.gst.canonical.application.document.SchemeFacts
import com.dynokore.gst.canonical.application.document.SchemeFactsAnswer
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.LocalDate

/** INV-AC-21475: the rules line evaluator and the masterdata counterparty scheme facts (JDK HttpServer stub). */
class ScopeEvaluationAdaptersTest {
    private val date = LocalDate.parse("2026-03-20")
    private val lookup = CounterpartyLookup("E1", "PRIVATE_LIMITED", "UEN", "201954321B", date)
    private val schemes = SchemeFacts(listOf("ARCS"), true)
    private val f1 = LineScopeFacts("E1", date, "SR-STD", listOf("ARCS"), schemes)
    private val f2 = f1.copy(counterpartyScheme = null)

    private fun evaluatorOver(answer: RulesAnswer): Pair<RulesLineEvaluator, MutableList<EvaluationFacts>> {
        val seen = mutableListOf<EvaluationFacts>()
        return RulesLineEvaluator {
            seen += it
            answer
        } to seen
    }

    @Test
    fun `INV-AC-21475 a Verdict is relayed and Unavailable and Refused 400 fail closed to Unavailable`() {
        evaluatorOver(RulesAnswer.Verdict("IN_SCOPE", "380")).first.evaluate(f1) shouldBe
            LineAnswer.Verdict("IN_SCOPE", documentTypeCode = "380")
        evaluatorOver(RulesAnswer.Unavailable).first.evaluate(f1) shouldBe LineAnswer.Unavailable
        evaluatorOver(RulesAnswer.Refused(400)).first.evaluate(f1) shouldBe LineAnswer.Unavailable
    }

    @Test
    fun `INV-AC-21475 the request for F1 carries the scheme data and the request for F2 has none`() {
        val (evaluator, seen) = evaluatorOver(RulesAnswer.Verdict("IN_SCOPE", "380"))
        evaluator.evaluate(f1)
        evaluator.evaluate(f2)
        seen shouldBe
            listOf(
                EvaluationFacts("E1", date, "SR-STD", listOf("ARCS"), SchemeData(listOf("ARCS"), true)),
                EvaluationFacts("E1", date, "SR-STD", listOf("ARCS"), null),
            )
        seen[1].counterpartySchemeData shouldBe null
    }

    private class Registry : ResilienceRegistry {
        override fun forEdge(edge: String): ResilientEdge =
            object : ResilientEdge {
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T = block()
            }
    }

    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun sourceAnswering(
        status: Int,
        body: String,
    ): MasterDataCounterpartySchemes {
        val stub =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") {
                    val bytes = body.toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) it.responseBody.write(bytes)
                    it.close()
                }
                start()
            }
        server = stub
        val env = MockEnvironment().withProperty(MasterDataEdge.URL_PROPERTY, "http://127.0.0.1:${stub.address.port}")
        return MasterDataCounterpartySchemes(MasterDataClient(RestClient.builder(), env, Registry()))
    }

    private val triple = """"identityTriple":{"senderId":"0195:1","supplierId":"0195:1","supplierGstn":"201912345A"}"""
    private val party = """"counterparty":{"partyRef":"CTP-118","resolvedIdentifiers":[],"providedBy":"INTERNAL"}"""
    private val m1 =
        """{$triple,$party,"approvedSchemes":[{"scheme":"ARCS","effectiveFrom":"2026-01-01"}],"arcsGstChargeAgreed":true}"""
    private val m2 = """{$triple,$party}"""
    private val envelope = """{"type":"about:blank","title":"x","status":404,"code":"SRT-005","correlationId":"c-1"}"""

    @Test
    fun `INV-AC-21475 M1 answers Found with ARCS and true`() {
        sourceAnswering(200, m1).schemeFactsOf(lookup) shouldBe
            SchemeFactsAnswer.Found(SchemeFacts(listOf("ARCS"), true))
    }

    @Test
    fun `INV-AC-21475 M2 answers Found with no schemes and no agreement`() {
        sourceAnswering(200, m2).schemeFactsOf(lookup) shouldBe SchemeFactsAnswer.Found(SchemeFacts(emptyList(), null))
    }

    @Test
    fun `INV-AC-21475 M3 a 404 answers Found null`() {
        sourceAnswering(404, envelope).schemeFactsOf(lookup) shouldBe SchemeFactsAnswer.Found(null)
    }

    @Test
    fun `INV-AC-21475 M3 an answer without a counterparty answers Found null`() {
        sourceAnswering(200, "{$triple}").schemeFactsOf(lookup) shouldBe SchemeFactsAnswer.Found(null)
    }

    @Test
    fun `INV-AC-21475 M4 a 500 answers Unavailable and so does a 400`() {
        sourceAnswering(500, "{}").schemeFactsOf(lookup) shouldBe SchemeFactsAnswer.Unavailable
        server?.stop(0)
        sourceAnswering(400, envelope).schemeFactsOf(lookup) shouldBe SchemeFactsAnswer.Unavailable
    }

    private fun window(
        from: String,
        to: String?,
        scheme: String = "ARCS",
    ) = ApprovedSchemeWindow(scheme, LocalDate.parse(from), to?.let { LocalDate.parse(it) })

    private fun schemesOver(vararg windows: ApprovedSchemeWindow): SchemeFactsAnswer {
        var handedDate: LocalDate? = null
        val source =
            MasterDataCounterpartySchemes { _, _, _, _, on ->
                handedDate = on
                MasterDataAnswer.Answered(ResolvedCounterparty("CTP-118", windows.toList(), true))
            }
        val answer = source.schemeFactsOf(lookup)
        handedDate shouldBe date
        return answer
    }

    private fun kept(vararg windows: ApprovedSchemeWindow): List<String> =
        (schemesOver(*windows) as SchemeFactsAnswer.Found).facts!!.approvedSchemes

    @Test
    fun `INV-AC-21475 a window of exactly the lookup date is kept, both bounds inclusive`() {
        kept(window("2026-03-20", "2026-03-20")) shouldBe listOf("ARCS")
    }

    @Test
    fun `INV-AC-21475 an item ending one day before the lookup date is dropped`() {
        kept(window("2026-01-01", "2026-03-19")) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21475 an item starting one day after the lookup date is dropped`() {
        kept(window("2026-03-21", null)) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21475 an item with no end date is kept`() {
        kept(window("2026-01-01", null)) shouldBe listOf("ARCS")
    }

    @Test
    fun `INV-AC-21475 two items of one scheme count only for the window that contains the date`() {
        kept(window("2025-01-01", "2025-12-31"), window("2026-01-01", null)) shouldBe listOf("ARCS")
        kept(window("2026-01-01", null), window("2026-03-01", "2026-04-01")) shouldBe listOf("ARCS", "ARCS")
    }

    @Test
    fun `INV-AC-21475 when every item is out of window the facts hold an empty list and the answer is Found`() {
        schemesOver(window("2025-01-01", "2025-12-31"), window("2026-04-01", null)) shouldBe
            SchemeFactsAnswer.Found(SchemeFacts(emptyList(), true))
    }
}
