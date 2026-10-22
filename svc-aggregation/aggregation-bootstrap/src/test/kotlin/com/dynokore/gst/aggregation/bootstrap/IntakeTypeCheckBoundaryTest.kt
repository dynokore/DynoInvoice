package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.domain.AggregateIntakeCheck
import com.dynokore.gst.aggregation.domain.IntakeVerdict
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The type check of `intakeAggregationBatch` at the service boundary (INV-REQ-410): the real controller and the
 * generated contract types behind the HTTP layer; no store is touched, so a refused call creates nothing.
 */
class IntakeTypeCheckBoundaryTest {
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(IntakeController())
            .setControllerAdvice(NotAggregatableAdvice())
            .build()
    private val mapper = ObjectMapper()

    private fun body(aggregateType: String) =
        """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"$aggregateType",
        |"periodStart":"2026-03-20","periodEnd":"2026-03-20","underlyingRef":"BATCH-9001",
        |"linesByInternalTaxCode":[{"internalTaxCode":"SR-STD","amount":"1500.00"}]}
        """.trimMargin()

    private fun call(
        aggregateType: String,
        vararg headers: Pair<String, String>,
    ) = mvc
        .perform(
            post("/internal/v1/aggregation/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .content(body(aggregateType)),
        ).andReturn()
        .response

    @Test
    fun `INV-AC-447 ordinary B2B invoices offered to the aggregate intake are refused with ING-006 and no override`() {
        listOf("B2B", "INVOICE", "pos", "").forEach { offered ->
            val response = call(offered)
            response.status shouldBe 422
            val problem = mapper.readTree(response.contentAsString)
            problem.get("code").asString() shouldBe "ING-006"
            problem.get("severity").asString() shouldBe "BLOCK"
            problem.get("disposition").asString() shouldBe "TERMINAL"
        }
    }

    @Test
    fun `INV-AC-447 an override header or flag does not change the refusal`() {
        val response = call("B2B", "X-Override" to "true", "X-Force" to "1", "X-Idempotency-Override" to "true")
        response.status shouldBe 422
        mapper.readTree(response.contentAsString).get("code").asString() shouldBe "ING-006"
    }

    @Test
    fun `POS, STI and PCP pass the type check and reach the operation, which answers 501 until its task`() {
        listOf("POS", "STI", "PCP").forEach { call(it).status shouldBe 501 }
    }

    @Test
    fun `INV-AC-1572 an aggregate whose underlying detail holds a B2B invoice is blocked with ING-006, terminal`() {
        val verdict = AggregateIntakeCheck.checkDetail(listOf("POS", "B2B", "POS"))
        val refusal = verdict.shouldBeInstanceOf<IntakeVerdict.Refused>().refusal
        refusal.code shouldBe "ING-006"
        refusal.terminal shouldBe true
        refusal.overridable shouldBe false
    }
}
