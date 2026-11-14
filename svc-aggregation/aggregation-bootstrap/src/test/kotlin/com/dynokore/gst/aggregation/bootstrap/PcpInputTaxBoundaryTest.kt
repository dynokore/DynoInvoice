package com.dynokore.gst.aggregation.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.nio.file.Path

/**
 * The input tax claimed on a PCP aggregate (INV-REQ-441): the sum of the claimable input tax the ERP stated per
 * claim, per internal tax code, read from the transactions posted through the intake operation.
 */
class PcpInputTaxBoundaryTest {
    private fun run(
        dir: Path,
        block: (ContentKit) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            block(ContentKit(context))
        }
    }

    private fun JsonNode.subtotals() =
        this["subtotalsByTaxCode"].toList().associate {
            it["internalTaxCode"].asString() to BigDecimal(it["amount"].asString())
        }

    private fun ContentKit.tableCounts(): List<String> =
        rows(
            "select (select count(*) from aggregation.aggregate_transaction where tenant_id = ?)::text || ',' || " +
                "(select count(*) from aggregation.aggregate where tenant_id = ?)::text || ',' || " +
                "(select count(*) from aggregation.aggregate_line where tenant_id = ?)::text",
            tenant,
            tenant,
        )

    @Test
    fun `INV-AC-1437 three claims under claimable portion sum to 3_50`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit
                .post(
                    listOf(
                        Txn("C1", "S", "10.00", claimable = "1.00"),
                        Txn("C2", "S", "10.00", claimable = "2.50"),
                        Txn("C3", "S", "10.00", claimable = "0.00"),
                    ),
                    type = "PCP",
                ).status shouldBe 202
            kit.close()
            kit.lines(kit.aggregateIds(creditNote = false).single()) shouldBe listOf("S=3.50")
        }

    @Test
    fun `INV-AC-1579 two claims under entire invoice sum to the whole input tax 4_00 and not 2_40`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit
                .post(
                    listOf(Txn("E1", "S", "20.00", claimable = "2.00"), Txn("E2", "S", "20.00", claimable = "2.00")),
                    type = "PCP",
                ).status shouldBe 202
            kit.close()
            kit.lines(kit.aggregateIds(creditNote = false).single()) shouldBe listOf("S=4.00")
        }

    @Test
    fun `INV-AC-22475 the subtotal per code is the claimable input tax in the page, the event and the items`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit
                .post(
                    listOf(
                        Txn("P1", "S", "40.00", claimable = "3.50"),
                        Txn("P2", "S", "40.00", claimable = "1.25"),
                        Txn("P3", "S", "40.00", claimable = "0.00"),
                        Txn("P4", "Z", "11.00", claimable = "2.00"),
                    ),
                    type = "PCP",
                ).status shouldBe 202
            kit.close()
            kit.handOn().size shouldBe 1
            val aggregate = kit.aggregateIds(creditNote = false).single()
            val page = kit.json(kit.drill(aggregate))
            val expected = mapOf("S" to BigDecimal("4.75"), "Z" to BigDecimal("2.00"))
            page.subtotals().mapValues { it.value.stripTrailingZeros() } shouldBe
                expected.mapValues { it.value.stripTrailingZeros() }
            val event = kit.ready().single()
            event["linesByInternalTaxCode"]
                .toList()
                .associate { it["internalTaxCode"].asString() to BigDecimal(it["amount"].asString()) }
                .mapValues { it.value.stripTrailingZeros() } shouldBe
                expected.mapValues { it.value.stripTrailingZeros() }
            val items = page["items"].toList()
            val byCode =
                items.groupBy { item ->
                    kit
                        .rows(
                            "select internal_tax_code from aggregation.aggregate_transaction where tenant_id = ? " +
                                "and transaction_ref = '${item["transactionRef"].asString()}'",
                        ).single()
                }
            byCode.mapValues { (_, list) ->
                list.sumOf { BigDecimal(it["claimableInputTax"].asString()) }.stripTrailingZeros()
            } shouldBe
                expected.mapValues { it.value.stripTrailingZeros() }
        }

    @Test
    fun `INV-AC-1437 a PCP item without claimableInputTax is refused 400 SRT-001 and nothing is stored`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            val before = kit.tableCounts()
            val response = kit.post(listOf(Txn("N1", "S", "10.00")), type = "PCP")
            response.status shouldBe 400
            kit.json(response)["code"].asString() shouldBe "SRT-001"
            kit.tableCounts() shouldBe before
        }

    @Test
    fun `INV-AC-1437 a POS or STI item with claimableInputTax is refused 400 SRT-001 and nothing is stored`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            val before = kit.tableCounts()
            listOf("POS", "STI").forEach { type ->
                val response = kit.post(listOf(Txn("N2", "S", "10.00", claimable = "1.00")), type = type)
                response.status shouldBe 400
                kit.json(response)["code"].asString() shouldBe "SRT-001"
            }
            kit.tableCounts() shouldBe before
        }
}
