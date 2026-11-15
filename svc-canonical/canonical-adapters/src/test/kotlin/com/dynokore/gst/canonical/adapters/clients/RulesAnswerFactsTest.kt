package com.dynokore.gst.canonical.adapters.clients

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

/** INV-AC-21596: the scope answer facts the rules client reads from `evaluateDocument` (JDK HttpServer stub). */
class RulesAnswerFactsTest {
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
    ) = """{"packType":"$type","version":"$version"}"""

    @Test
    fun `INV-AC-21596 K1 the verdict carries the SCOPE and MAPPING versions, category, rate and type, no MDE`() {
        val items = listOf(item("SCOPE", "S-2026.1"), item("MAPPING", "M-3")).joinToString(",")
        answerOf(items) shouldBe
            RulesAnswer.Verdict(
                scopeVerdict = "IN_SCOPE",
                documentTypeCode = "380",
                categoryCode = "SR",
                rate = "9.00",
                scopePackVersion = "S-2026.1",
                mappingPackVersion = "M-3",
                mdePackVersion = null,
            )
    }

    @Test
    fun `INV-AC-21596 K1 the MDE version comes from the MDE item`() {
        val items = listOf(item("MDE", "D-7"), item("MAPPING", "M-3"), item("SCOPE", "S-1")).joinToString(",")
        val answer = answerOf(items) as RulesAnswer.Verdict
        answer.mdePackVersion shouldBe "D-7"
        answer.mappingPackVersion shouldBe "M-3"
        answer.scopePackVersion shouldBe "S-1"
    }

    @Test
    fun `INV-AC-21596 K2 look-alike pack types are not matched`() {
        val items = listOf(item("SCOPE_TEST", "S-X"), item("scope", "S-Y"), item("MAPPING", "M-3")).joinToString(",")
        val answer = answerOf(items) as RulesAnswer.Verdict
        answer.scopePackVersion shouldBe null
        answer.mdePackVersion shouldBe null
        answer.mappingPackVersion shouldBe "M-3"
        (answerOf(item("Mde", "D-1") + "," + item("MDE ", "D-2")) as RulesAnswer.Verdict).mdePackVersion shouldBe null
    }

    @Test
    fun `INV-AC-21596 K9 two SCOPE items with different versions are Unavailable`() {
        answerOf(item("SCOPE", "S-1") + "," + item("SCOPE", "S-2")) shouldBe RulesAnswer.Unavailable
        answerOf(item("MDE", "D-1") + "," + item("MDE", "D-2")) shouldBe RulesAnswer.Unavailable
    }

    @Test
    fun `INV-AC-21596 K9 two SCOPE items with the same version are one version`() {
        val answer = answerOf(item("SCOPE", "S-1") + "," + item("SCOPE", "S-1")) as RulesAnswer.Verdict
        answer.scopePackVersion shouldBe "S-1"
    }

    @Test
    fun `INV-AC-21596 K1 the line evaluator copies every fact`() {
        val v = RulesAnswer.Verdict("IN_SCOPE", "380", "SR", "9.00", "S-1", "M-3", "D-7")
        val f = LineScopeFacts("E1", LocalDate.parse("2026-03-20"), "SR-STD", listOf("ARCS"), null)
        RulesLineEvaluator { v }.evaluate(f) shouldBe
            LineAnswer.Verdict("IN_SCOPE", "SR", "9.00", "S-1", "M-3", "D-7", "380")
    }
}
