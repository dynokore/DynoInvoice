package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.application.document.AppliedPackFact
import com.dynokore.gst.canonical.application.document.LineAnswer
import com.dynokore.gst.canonical.application.document.LineScopeFacts
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

/** INV-AC-22910: the applied packs with their packIdentifier, read from `evaluateDocument` (JDK HttpServer stub). */
class RulesAppliedPacksTest {
    @Volatile
    private var body = ""
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") {
                val bytes = body.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
                it.close()
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private val registry =
        ResilienceRegistry {
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

    private val facts =
        EvaluationFacts("E-001", LocalDate.parse("2026-03-20"), "SR-STD", listOf("ARCS"), null)

    private fun answerOf(items: String): RulesAnswer {
        body =
            """{"scopeVerdict":"IN_SCOPE","categoryCode":"SR","rate":"9.00","documentTypeCode":"380",""" +
            """"packVersionsApplied":[$items]}"""
        val url = "http://127.0.0.1:${server.address.port}"
        val env = MockEnvironment().also { it.setProperty(RulesEdge.URL_PROPERTY, url) }
        return RulesClient(RestClient.builder(), registry, env).evaluate(facts)
    }

    private fun item(
        type: String,
        version: String,
        id: String? = null,
    ) = if (id == null) {
        """{"packType":"$type","version":"$version"}"""
    } else {
        """{"packType":"$type","version":"$version","packIdentifier":"$id"}"""
    }

    private val scope = item("SCOPE", "2026.3", "gst-scope")
    private val mapping = item("MAPPING", "7", "MAP-0042")
    private val catalogue = item("CODE_CATALOGUE", "4", "gst-catalogue")

    private fun packsOf(items: String) = (answerOf(items) as RulesAnswer.Verdict).appliedPacks

    @Test
    fun `INV-AC-22910 K1 the list holds SCOPE and MAPPING in order and ignores CODE_CATALOGUE`() {
        val expected =
            listOf(AppliedPackFact("SCOPE", "gst-scope", "2026.3"), AppliedPackFact("MAPPING", "MAP-0042", "7"))
        packsOf("$scope,$mapping,$catalogue") shouldBe expected
        packsOf("$catalogue,$mapping,$scope") shouldBe expected
    }

    @Test
    fun `INV-AC-22910 K2 the MDE item follows the other two`() {
        val mde = item("MDE", "D-7", "mde-1")
        packsOf("$mde,$mapping,$scope").map { it.packType } shouldBe listOf("SCOPE", "MAPPING", "MDE")
    }

    @Test
    fun `INV-AC-22910 K3 a SCOPE item without an identifier gives Unavailable`() {
        answerOf("${item("SCOPE", "2026.3")},$mapping,$catalogue") shouldBe RulesAnswer.Unavailable
    }

    @Test
    fun `INV-AC-22910 K4 a blank identifier gives Unavailable`() {
        answerOf("${item("SCOPE", "2026.3", " ")},$mapping") shouldBe RulesAnswer.Unavailable
        answerOf("$scope,${item("MAPPING", "7", "")}") shouldBe RulesAnswer.Unavailable
    }

    @Test
    fun `INV-AC-22910 K5 no identifier is derived from the pack type or the version`() {
        answerOf(item("SCOPE", "2026.3")) shouldBe RulesAnswer.Unavailable
        packsOf(scope).single().packIdentifier shouldBe "gst-scope"
    }

    @Test
    fun `INV-AC-22910 K6 an item without identifier of an ignored type does not matter`() {
        packsOf("$scope,${item("CODE_CATALOGUE", "4")}") shouldBe
            listOf(AppliedPackFact("SCOPE", "gst-scope", "2026.3"))
    }

    @Test
    fun `INV-AC-22910 K7 look-alike pack types are not matched, even with an identifier`() {
        packsOf("$scope,${item("SCOPE_TEST", "x", "i")},${item("scope", "y", "j")}").map { it.packIdentifier } shouldBe
            listOf("gst-scope")
    }

    @Test
    fun `INV-AC-22910 K9 look-alike pack types without identifier are ignored, not Unavailable`() {
        val expected = listOf(AppliedPackFact("SCOPE", "gst-scope", "2026.3"))
        packsOf("$scope,${item("SCOPE_TEST", "x")},${item("MAPPING_V2", "y")}") shouldBe expected
        packsOf("$scope,${item("scope", "y")},${item("Mde", "z")},${item("MDE ", "w")}") shouldBe expected
    }

    @Test
    fun `INV-AC-22910 K8 the line evaluator copies the list`() {
        val packs = listOf(AppliedPackFact("SCOPE", "gst-scope", "2026.3"))
        val v = RulesAnswer.Verdict("IN_SCOPE", "380", "SR", "9.00", "2026.3", null, null, packs)
        val f = LineScopeFacts("E1", LocalDate.parse("2026-03-20"), "SR-STD", listOf("ARCS"), null)
        (RulesLineEvaluator { v }.evaluate(f) as LineAnswer.Verdict).appliedPacks shouldBe packs
    }
}
