package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.PeriodCalendarUnavailableException
import com.dynokore.gst.rules.application.SamplePage
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

private const val D0 = "0d000000-0000-4000-8000-000000000000"
private const val D1 = "0d000000-0000-4000-8000-000000000001"
private const val D2 = "0d000000-0000-4000-8000-000000000002"
private const val D3 = "0d000000-0000-4000-8000-000000000003"

/** `supplyType` and `supplyTypeTotals` of the dry-run result at the HTTP boundary (INV-T-10748, INV-Q-10703 B'). */
@SpringBootTest(
    classes = [DryRunFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [WAIT_PROPERTY, IDEMPOTENCY, CALLER_0, SUBJECT_0, CALLER_1, SUBJECT_1],
)
class DryRunSupplyTypeBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired private val samples: FixtureHistoricalSamplePort,
    @Autowired private val periods: FixtureLastCompletePeriodPort,
    @Autowired private val evaluation: FixtureEvaluation,
) : DryRunSupport(port) {
    @BeforeEach
    @AfterEach
    fun reset() {
        samples.clear()
        periods.reset()
        evaluation.reset()
    }

    // ---- supplyType and supplyTypeTotals over HTTP (INV-T-10748, INV-Q-10703 B') ----

    private fun changed(vararg docs: Pair<String, Map<String, Any?>>): JsonNode {
        samples.serve("2026-Q3", docs.map { it.second })
        docs.forEach { evaluation.candidate[it.first] = DocumentOutcome("ZR") }
        return finished(candidate()).get("result")
    }

    private fun typeOf(
        type: String,
        doc: String,
        taxable: String = "100.00",
        gst: String = "9.00",
    ) = doc to (item(doc, "2026-Q3", taxable, gst) + ("supplyType" to type))

    private fun JsonNode.totalsList() =
        elements(get("supplyTypeTotals")).map {
            listOf(
                it.get("supplyType").asString(),
                it.get("documentCount").asInt(),
                it.get("taxableValue").asString(),
                it.get("gstValue").asString(),
                it.get("valuesAbsentCount").asInt(),
            )
        }

    @Test
    fun `INV-AC-9558 supplyTypeTotals over two changed types give the criterion numbers and name no identifier`() {
        val unchanged =
            listOf(
                item(D3, "2026-Q3") + ("supplyType" to "EXEMPT"),
                item(D0, "2026-Q3") + ("supplyType" to "IMPORTS"),
            )
        val one = typeOf("STANDARD_RATED", D1, "1000.00", "90.00")
        val two = typeOf("ZERO_RATED", D2, "250.00", "22.50")
        samples.serve("2026-Q3", listOf(one.second, two.second) + unchanged)
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        evaluation.candidate[D2] = DocumentOutcome("ZR")
        val result = finished(candidate()).get("result")
        result.totalsList() shouldBe
            listOf(listOf("STANDARD_RATED", 1, "1000.00", "90.00", 0), listOf("ZERO_RATED", 1, "250.00", "22.50", 0))
        elements(result.get("supplyTypeTotals")).all {
            it.propertyNames().asSequence().toSet() ==
                setOf("supplyType", "documentCount", "taxableValue", "gstValue", "valuesAbsentCount")
        } shouldBe true
    }

    @Test
    fun `an unknown member and the look-alikes are carried unchanged, listed after the five in string order`() {
        val result =
            changed(
                typeOf("ZERO_RATED", D0),
                typeOf("DEEMED_SUPPLY", D1),
                typeOf("zero_rated", D2),
                typeOf("ZERO-RATED", D3),
            )
        result.totalsList().map { it[0] } shouldBe listOf("ZERO_RATED", "DEEMED_SUPPLY", "ZERO-RATED", "zero_rated")
        elements(result.get("sample")).map { it.get("supplyType").asString() }.toSet() shouldBe
            setOf("ZERO_RATED", "DEEMED_SUPPLY", "zero_rated", "ZERO-RATED")
        elements(result.get("supplyTypeTotals")).sumOf { it.get("documentCount").asInt() } shouldBe
            result.get("changedCount").asInt()
    }

    @Test
    fun `each sample item carries the supplyType of its page item and the totals cross-check the change types`() {
        val result = changed(typeOf("EXEMPT", D1, "5.00", "0.00"), typeOf("IMPORTS", D2, "7.50", "0.68"))
        elements(
            result.get("sample"),
        ).associate { it.get("docUuid").asString() to it.get("supplyType").asString() } shouldBe
            mapOf(D1 to "EXEMPT", D2 to "IMPORTS")

        fun sum(
            array: String,
            member: String,
        ) = elements(result.get(array)).sumOf { java.math.BigDecimal(it.get(member).asString()) }
        sum("supplyTypeTotals", "taxableValue") shouldBe sum("changeTypeTotals", "taxableValue")
        sum("supplyTypeTotals", "gstValue") shouldBe sum("changeTypeTotals", "gstValue")
    }

    @Test
    fun `a flagged document is listed with its type and 0 00, counted, and left out of both sums`() {
        val flagged = item(D1, "2026-Q3", "777.00", "70.00") + ("sgdValuesAbsent" to true)
        val result = changed(D1 to flagged, typeOf("STANDARD_RATED", D2, "100.00", "9.00"))
        result.totalsList() shouldBe listOf(listOf("STANDARD_RATED", 2, "100.00", "9.00", 1))
        val listed = elements(result.get("sample")).first { it.get("docUuid").asString() == D1 }
        listed.get("supplyType").asString() shouldBe "STANDARD_RATED"
        listed.get("sgdValuesAbsent").asBoolean() shouldBe true
        listed.get("taxableValue").asString() shouldBe "0.00"
        listed.get("gstValue").asString() shouldBe "0.00"
        elements(result.get("sample")).first { it.get("docUuid").asString() == D2 }.has("sgdValuesAbsent") shouldBe
            false
    }

    @Test
    fun `a delta that changes no document gives an empty supplyTypeTotals`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3")))
        val result = finished(candidate()).get("result")
        result.get("changedCount").asInt() shouldBe 0
        result.get("supplyTypeTotals").size() shouldBe 0
    }
}
