package com.dynokore.gst.aggregation.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate

/** The exclusion of an STI receipt that a full tax invoice replaced (INV-REQ-440), through the real controller. */
class ReplacedReceiptBoundaryTest {
    private fun run(
        dir: Path,
        block: (ContentKit) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            block(ContentKit(context))
        }
    }

    private fun ContentKit.rowOf(aggregateId: String) =
        rows(
            "select row_to_json(a)::text from aggregation.aggregate a " +
                "where tenant_id = ? and aggregate_id = '$aggregateId'",
        ).single()

    private fun ContentKit.outcome(
        entity: String,
        receipt: String,
    ) = exclude(entity, receipt).let { response -> response.status to json(response) }

    @Test
    fun `INV-AC-458 a replaced receipt is excluded, not aggregated, or refused with ING-014 once sent`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit.post(listOf(Txn("R-1", "S", "10.00"), Txn("R-2", "Z", "5.00")), type = "STI").status shouldBe 202
            // the receipt of another entity with the same number
            kit.post(listOf(Txn("R-1", "S", "99.00")), type = "STI", entity = "E-002").status shouldBe 202
            kit.close()
            val aggregate = kit.aggregateIds(creditNote = false, entity = "E-001").single()
            val other = kit.aggregateIds(creditNote = false, entity = "E-002").single()
            kit.lines(aggregate) shouldBe listOf("S=10.00", "Z=5.00")

            // (a) not yet transmitted: EXCLUDED, removed from the aggregate, a repeat answers the same
            val (status, body) = kit.outcome("E-001", "R-1")
            status shouldBe 200
            body["outcome"].asString() shouldBe "EXCLUDED"
            body["receiptNumber"].asString() shouldBe "R-1"
            kit.lines(aggregate) shouldBe listOf("Z=5.00")
            kit.outcome("E-001", "R-1").second["outcome"].asString() shouldBe "EXCLUDED"
            // the receipt of another entity is untouched
            kit.lines(other) shouldBe listOf("S=99.00")

            // in no aggregate
            val (none, unknown) = kit.outcome("E-001", "R-404")
            none shouldBe 200
            unknown["outcome"].asString() shouldBe "NOT_AGGREGATED"

            // (b) transmitted: 409 with ING-014, nothing is amended
            kit.update(
                "update aggregation.aggregate set transmitted_at = now() where tenant_id = '${kit.tenant}' " +
                    "and aggregate_id = '$aggregate'",
            )
            val before = kit.rowOf(aggregate)
            val (conflict, problem) = kit.outcome("E-001", "R-2")
            conflict shouldBe 409
            problem["code"].asString() shouldBe "ING-014"
            problem["type"].asString().endsWith("/ING-014") shouldBe true
            problem["disposition"].asString() shouldBe "MANUAL"
            problem["remediationOwner"].asString() shouldBe "Finance"
            kit.rowOf(aggregate) shouldBe before
            kit.lines(aggregate) shouldBe listOf("Z=5.00")
        }

    @Test
    fun `INV-AC-458 a handed-on but untransmitted aggregate answers 409 and is not amended`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit.post(listOf(Txn("R-7", "S", "10.00"), Txn("R-8", "Z", "5.00")), type = "STI").status shouldBe 202
            kit.close()
            val aggregate = kit.aggregateIds(creditNote = false).single()
            kit.update(
                "update aggregation.aggregate set handed_on_at = now() where tenant_id = '${kit.tenant}' " +
                    "and aggregate_id = '$aggregate' and transmitted_at is null",
            )
            kit.rows(
                "select (transmitted_at is null)::text from aggregation.aggregate a where tenant_id = ? " +
                    "and aggregate_id = '$aggregate'",
            ) shouldBe listOf("true")
            val before = kit.rowOf(aggregate)
            val (status, problem) = kit.outcome("E-001", "R-7")
            status shouldBe 409
            problem["code"].asString() shouldBe "ING-014"
            kit.rowOf(aggregate) shouldBe before
            kit.lines(aggregate) shouldBe listOf("S=10.00", "Z=5.00")
        }

    @Test
    fun `INV-AC-458 a receipt that matches an item of a POS aggregate is not excluded`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit.post(listOf(Txn("R-9", "S", "7.00")), type = "POS").status shouldBe 202
            kit.close()
            val aggregate = kit.aggregateIds(creditNote = false).single()
            val (status, body) = kit.outcome("E-001", "R-9")
            status shouldBe 200
            body["outcome"].asString() shouldBe "NOT_AGGREGATED"
            kit.lines(aggregate) shouldBe listOf("S=7.00")
        }

    @Test
    fun `INV-AC-458 a receipt excluded before its window closes is never aggregated`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit.post(listOf(Txn("R-5", "S", "8.00", LocalDate.of(2022, 3, 10))), type = "STI").status shouldBe 202
            val (status, body) = kit.outcome("E-001", "R-5")
            status shouldBe 200
            body["outcome"].asString() shouldBe "NOT_AGGREGATED"
            kit.close()
            val aggregate = kit.aggregateIds(creditNote = false).single()
            kit.lines(aggregate) shouldBe emptyList()
        }

    private fun ContentKit.recorded(
        entity: String,
        receipt: String,
    ) = rows(
        "select outcome from aggregation.replaced_receipt where tenant_id = ? and entity_ref = '$entity' " +
            "and receipt_number = '$receipt'",
    )

    @Test
    fun `INV-AC-458 a repeat call answers the first outcome and the table holds one row`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit.post(listOf(Txn("R-1", "S", "10.00")), type = "STI").status shouldBe 202
            kit.close()
            kit.outcome("E-001", "R-404").second["outcome"].asString() shouldBe "NOT_AGGREGATED"
            // the receipt arrives after the first call; the recorded outcome is still answered
            kit.post(listOf(Txn("R-404", "S", "3.00")), type = "STI").status shouldBe 202
            kit.outcome("E-001", "R-404").second["outcome"].asString() shouldBe "NOT_AGGREGATED"
            kit.outcome("E-001", "R-1").second["outcome"].asString() shouldBe "EXCLUDED"
            kit.outcome("E-001", "R-1").second["outcome"].asString() shouldBe "EXCLUDED"
            kit.recorded("E-001", "R-1") shouldBe listOf("EXCLUDED")
            kit.recorded("E-001", "R-404") shouldBe listOf("NOT_AGGREGATED")
        }

    @Test
    fun `INV-AC-458 an aggregate emptied by exclusions keeps its row, has no line and is not handed on`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit.post(listOf(Txn("R-1", "S", "10.00"), Txn("R-2", "Z", "5.00")), type = "STI").status shouldBe 202
            kit.close()
            val aggregate = kit.aggregateIds(creditNote = false).single()
            kit.outcome("E-001", "R-1").first shouldBe 200
            kit.outcome("E-001", "R-2").first shouldBe 200
            kit.aggregateIds(creditNote = false) shouldBe listOf(aggregate)
            kit.lines(aggregate) shouldBe emptyList()
            kit.handOn() shouldBe emptyList()
            kit.ready() shouldBe emptyList()
            kit.rows(
                "select (handed_on_at is null)::text from aggregation.aggregate where tenant_id = ? " +
                    "and aggregate_id = '$aggregate'",
            ) shouldBe listOf("true")
        }

    @Test
    fun `INV-AC-458 the 409 is recorded nowhere and a repeat answers 409 again`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit.post(listOf(Txn("R-2", "Z", "5.00")), type = "STI").status shouldBe 202
            kit.close()
            val aggregate = kit.aggregateIds(creditNote = false).single()
            kit.update(
                "update aggregation.aggregate set transmitted_at = now() where tenant_id = '${kit.tenant}' " +
                    "and aggregate_id = '$aggregate'",
            )
            kit.outcome("E-001", "R-2").first shouldBe 409
            kit.recorded("E-001", "R-2") shouldBe emptyList()
            kit.outcome("E-001", "R-2").first shouldBe 409
            kit.rows("select count(*)::text from aggregation.replaced_receipt where tenant_id = ?") shouldBe listOf("0")
            kit.lines(aggregate) shouldBe listOf("Z=5.00")
        }
}
