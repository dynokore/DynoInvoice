package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.adapters.http.TransactionIntakeController
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.ExcludeReplacedReceipt
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.domain.Cadence
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.JsonNode
import java.nio.file.Path
import java.util.UUID

/**
 * The optional taxableAmount of a PCP transaction item and batch line (INV-REQ-437, INV-AC-23682): carried into the
 * AggregateReady line, refused with SRT-001 for POS and STI, and a replay stores nothing.
 */
class PcpTaxableAmountBoundaryTest {
    /** One item as the request carries it: amount, claimableInputTax (PCP only) and the optional taxableAmount. */
    private data class Item(
        val ref: String,
        val code: String,
        val amount: String,
        val claimable: String?,
        val taxable: String?,
    ) {
        fun json() =
            """{"transactionRef":"$ref","transactionDate":"2022-03-10","internalTaxCode":"$code","amount":"$amount"""" +
                claimable?.let { ""","claimableInputTax":"$it"""" }.orEmpty() +
                taxable?.let { ""","taxableAmount":"$it"""" }.orEmpty() + "}"
    }

    private class Routes(
        val kit: ContentKit,
    ) {
        private val mvc: MockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    TransactionIntakeController(
                        kit.context.getBean(IntakeAggregate::class.java),
                        CadenceSource { _, _, _ -> Cadence.DAILY },
                        kit.context.getBean(WindowRepository::class.java),
                        kit.context.getBean(JooqTransactionStore::class.java),
                        kit.context.getBean(ExcludeReplacedReceipt::class.java),
                    ),
                ).setControllerAdvice(NotAggregatableAdvice())
                .addFilters<StandaloneMockMvcBuilder>(IntakeBodyCachingFilter())
                .build()

        private fun send(
            path: String,
            content: String,
        ): MockHttpServletResponse =
            kit.bound {
                mvc
                    .perform(
                        post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .header("X-Flow-Context", "{}")
                            .content(content),
                    ).andReturn()
                    .response
            }

        fun transactions(
            items: List<Item>,
            type: String,
            sourceKey: String = "K-${UUID.randomUUID()}",
        ) = send(
            "/internal/v1/aggregation/transactions",
            """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"$type","underlyingRef":"BATCH-1",
            |"sourceKey":"$sourceKey","sourceRevision":1,"transactions":[${items.joinToString(",") { it.json() }}]}
            """.trimMargin(),
        )

        fun batch(
            type: String,
            line: String,
        ) = send(
            "/internal/v1/aggregation/batches",
            """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"$type",
            |"periodStart":"2022-03-01","periodEnd":"2022-03-31","underlyingRef":"BATCH-9001",
            |"sourceKey":"K-${UUID.randomUUID()}","sourceRevision":1,"linesByInternalTaxCode":[$line]}
            """.trimMargin(),
        )
    }

    private fun run(
        dir: Path,
        block: (ContentKit, Routes) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            val kit = ContentKit(context)
            block(kit, Routes(kit))
        }
    }

    private fun ContentKit.tableCounts(): List<String> =
        rows(
            "select (select count(*) from aggregation.aggregate_transaction where tenant_id = ?)::text || ',' || " +
                "(select count(*) from aggregation.aggregate where tenant_id = ?)::text || ',' || " +
                "(select count(*) from aggregation.aggregate_line where tenant_id = ?)::text",
            tenant,
            tenant,
        )

    private fun JsonNode.line(code: String): JsonNode =
        this["linesByInternalTaxCode"].toList().single { it["internalTaxCode"].asString() == code }

    private val full =
        listOf(
            Item("T1", "S1", "109.00", "9.00", "100.00"),
            Item("T2", "S1", "54.00", "4.00", "50.00"),
            Item("T3", "Z1", "20.00", "0.00", "20.00"),
        )

    @Test
    fun `INV-AC-23682 PCP transactions with taxableAmount give event lines with the exact sums`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit, routes ->
            routes.transactions(full, "PCP").status shouldBe 202
            kit.close()
            kit.handOn().size shouldBe 1
            val event = kit.ready().single()
            event.line("S1")["amount"].asString() shouldBe "13.00"
            event.line("S1")["taxableAmount"].asString() shouldBe "150.00"
            event.line("Z1")["amount"].asString() shouldBe "0.00"
            event.line("Z1")["taxableAmount"].asString() shouldBe "20.00"
        }

    @Test
    fun `INV-AC-23682 a PCP item without taxableAmount leaves the member out of the event line`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit, routes ->
            routes.transactions(full.take(1) + full[1].copy(taxable = null) + full[2], "PCP").status shouldBe 202
            kit.close()
            kit.handOn().size shouldBe 1
            val event = kit.ready().single()
            event.line("S1")["amount"].asString() shouldBe "13.00"
            event.line("S1").has("taxableAmount") shouldBe false
        }

    @Test
    fun `INV-AC-23682 PCP batch line carries taxableAmount into the event`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit, routes ->
            routes
                .batch("PCP", """{"internalTaxCode":"S1","amount":"13.00","taxableAmount":"150.00"}""")
                .status shouldBe 202
            kit.handOn().size shouldBe 1
            val line = kit.ready().single().line("S1")
            line["amount"].asString() shouldBe "13.00"
            line["taxableAmount"].asString() shouldBe "150.00"
        }

    @Test
    fun `INV-AC-23682 POS and STI items with taxableAmount are refused 400 SRT-001 and nothing is stored`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit, routes ->
            val before = kit.tableCounts()
            listOf("POS", "STI").forEach { type ->
                val response = routes.transactions(listOf(Item("N1", "S1", "100.00", null, "100.00")), type)
                response.status shouldBe 400
                kit.json(response)["code"].asString() shouldBe "SRT-001"
            }
            kit.tableCounts() shouldBe before
        }

    @Test
    fun `INV-AC-23682 POS batch line with taxableAmount is refused 400 SRT-001 and nothing is stored`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit, routes ->
            val before = kit.tableCounts()
            val response =
                routes.batch("POS", """{"internalTaxCode":"S1","amount":"100.00","taxableAmount":"100.00"}""")
            response.status shouldBe 400
            kit.json(response)["code"].asString() shouldBe "SRT-001"
            kit.tableCounts() shouldBe before
        }

    @Test
    fun `INV-AC-23682 replay of the PCP transaction request answers the first receipt and stores no second row`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit, routes ->
            val first = routes.transactions(full, "PCP", sourceKey = "K-REPLAY")
            first.status shouldBe 202
            val counts = kit.tableCounts()
            val replay = routes.transactions(full, "PCP", sourceKey = "K-REPLAY")
            replay.status shouldBe 202
            replay.contentAsString shouldBe first.contentAsString
            kit.tableCounts() shouldBe counts
        }
}
