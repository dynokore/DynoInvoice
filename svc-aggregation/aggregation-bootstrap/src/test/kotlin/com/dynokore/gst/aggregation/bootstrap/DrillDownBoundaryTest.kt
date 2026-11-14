package com.dynokore.gst.aggregation.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** The drill-down from an aggregate to its retained underlying transactions (INV-REQ-414, INV-REQ-884). */
class DrillDownBoundaryTest {
    private fun run(
        dir: Path,
        block: (ContentKit) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            block(ContentKit(context))
        }
    }

    private fun JsonNode.refs() = this["items"].toList().map { it["transactionRef"].asString() }

    /** A with T1 inserted first under the later underlyingRef, then T3 and T2 under the earlier one; B with two. */
    private fun ContentKit.seed(): Pair<String, String> {
        post(listOf(Txn("T1", "S", "1.00")), underlyingRef = "U-B").status shouldBe 202
        post(listOf(Txn("T3", "S", "3.00"), Txn("T2", "Z", "2.00")), underlyingRef = "U-A").status shouldBe 202
        post(listOf(Txn("X1", "S", "9.00"), Txn("X2", "S", "9.50")), entity = "E-002").status shouldBe 202
        close()
        return aggregateIds(creditNote = false, "E-001").single() to aggregateIds(creditNote = false, "E-002").single()
    }

    @Test
    fun `INV-AC-22471 the pages of the drill-down hold every retained transaction in the documented order`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            val (a, b) = kit.seed()
            val first = kit.drill(a, limit = 2)
            first.status shouldBe 200
            val one = kit.json(first)
            // order underlyingRef then transactionRef; never the insertion order (T1 came first)
            one.refs() shouldBe listOf("T2", "T3")
            one["underlyingCount"].asInt() shouldBe 3
            val cursor = one["nextCursor"].asString()
            val two = kit.json(kit.drill(a, limit = 2, cursor = cursor))
            two.refs() shouldBe listOf("T1")
            // the last page has no nextCursor
            two.has("nextCursor") shouldBe false
            (one.refs() + two.refs()).size shouldBe two["underlyingCount"].asInt()
            // no item of B appears in A's pages
            (one.refs() + two.refs()).any { it.startsWith("X") } shouldBe false
            // B's own pages
            kit.json(kit.drill(b, limit = 100)).refs() shouldBe listOf("X1", "X2")
            // the cursor of A is refused for B
            kit.drill(b, limit = 2, cursor = cursor).status shouldBe 400
        }

    @Test
    fun `INV-AC-22471 a forged or foreign cursor, a limit above 500 and an unknown aggregate are refused`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            val (a, _) = kit.seed()
            val cursor = kit.json(kit.drill(a, limit = 1))["nextCursor"].asString()
            kit.drill(a, limit = 1, cursor = cursor).status shouldBe 200
            kit.drill(a, limit = 1, cursor = cursor.dropLast(2) + "xx").status shouldBe 400
            kit.drill(a, limit = 1, cursor = "not-a-cursor").status shouldBe 400
            kit.drill(a, limit = 501).status shouldBe 400
            kit.drill(a, limit = 0).status shouldBe 400
            kit.actor = "user:someone-else"
            kit.drill(a, limit = 1, cursor = cursor).status shouldBe 400
            kit.drill(UUID.randomUUID().toString()).status shouldBe 404
            kit.drill("not-an-id").status shouldBe 400
        }

    @Test
    fun `INV-AC-22471 with the cursor key secret absent no page carries a nextCursor`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            val (a, _) = kit.seed()
            // key mounted: the first page of two issues a cursor
            val cursor = kit.json(kit.drill(a, limit = 1))["nextCursor"].asString()
            // the key is removed from the mounted store: read at every use, so the next request sees it absent
            Files.delete(dir.resolve("aggregation/drill-down-cursor-key"))
            val pages =
                listOf(kit.drill(a, limit = 1), kit.drill(a, limit = 2), kit.drill(a, limit = 1, cursor = cursor))
            // fail closed: the page that would need a cursor is refused with the 500 envelope, nothing is issued
            pages.map { it.status } shouldBe listOf(500, 500, 500)
            pages.any { it.contentAsString.contains("nextCursor") } shouldBe false
            // a page that needs no cursor is still served, and carries none
            val last = kit.drill(a, limit = 100)
            last.status shouldBe 200
            kit.json(last).has("nextCursor") shouldBe false
        }

    @Test
    fun `INV-AC-448 a transmitted aggregate lists all its underlying transactions, counted as declared`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            val (a, _) = kit.seed()
            kit.handOn().size shouldBe 1
            kit.update("update aggregation.aggregate set transmitted_at = now() where tenant_id = '${kit.tenant}'")
            val page = kit.json(kit.drill(a))
            page.refs() shouldBe listOf("T2", "T3", "T1")
            page["underlyingCount"].asInt() shouldBe page.refs().size
        }

    @Test
    fun `INV-AC-22472 the claimant reference posted on items is returned by the drill-down and no name is kept`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit
                .post(
                    listOf(
                        Txn("C1", "S", "5.00", claimant = "CLM-0042", claimable = "1.00"),
                        Txn("C2", "S", "6.00", claimant = "CLM-0043", claimable = "2.00"),
                    ),
                    type = "PCP",
                ).status shouldBe 202
            kit.close()
            val a = kit.aggregateIds(creditNote = false).single()
            val items = kit.json(kit.drill(a))["items"].toList()
            items.associate { it["transactionRef"].asString() to it["claimantReference"].asString() } shouldBe
                mapOf("C1" to "CLM-0042", "C2" to "CLM-0043")
            items.forEach { item ->
                item.propertyNames().toList().none { it.contains("name", ignoreCase = true) } shouldBe true
            }
        }

    @Test
    fun `INV-AC-1916 the retained detail of an expense batch holds claimant references only`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit
                .post(listOf(Txn("C9", "S", "5.00", claimant = "CLM-0099", claimable = "5.00")), type = "PCP")
                .status shouldBe 202
            kit.rows(
                "select claimant_reference from aggregation.aggregate_transaction where tenant_id = ? " +
                    "and transaction_ref = 'C9'",
            ) shouldBe listOf("CLM-0099")
            kit.rows(
                "select column_name from information_schema.columns where table_schema = 'aggregation' " +
                    "and table_name = 'aggregate_transaction' and column_name ilike '%name%' and ? is not null",
            ) shouldBe emptyList()
        }
}
