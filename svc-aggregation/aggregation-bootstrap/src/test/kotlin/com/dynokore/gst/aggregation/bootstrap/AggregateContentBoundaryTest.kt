package com.dynokore.gst.aggregation.bootstrap

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Credit-note aggregates and one line per distinct internal tax code (INV-REQ-437, INV-REQ-438), over the composed
 * service: transactions posted through the real controller, the closing run, the hand-off and the outbox.
 */
class AggregateContentBoundaryTest {
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }.getSchema(
                File(
                    checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" },
                    "schemas/aggregate-ready-payload.schema.json",
                ).readText(),
            )

    private fun run(
        dir: Path,
        block: (ContentKit) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            block(ContentKit(context))
        }
    }

    /** The aggregate row without the columns that differ between two aggregates by construction. */
    private fun ContentKit.row(aggregateId: String) =
        rows(
            "select (row_to_json(a)::jsonb - 'aggregate_id' - 'tenant_id' - 'created_at' - 'sequence' " +
                "- 'document_number' - 'source_key')::text from aggregation.aggregate a " +
                "where tenant_id = ? and aggregate_id = '$aggregateId'",
        ).single()

    @Test
    fun `INV-AC-455 sales and refunds of one window give two aggregates and the sales are not netted`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            // the control: the same sales in a tenant with no refunds
            val control = ContentKit(kit.context)
            control.post(listOf(Txn("S-1", "S", "100.00")), sourceKey = "SALES").status shouldBe 202
            control.close()
            val controlSales = control.aggregateIds(creditNote = false).single()

            kit.post(listOf(Txn("S-1", "S", "100.00")), sourceKey = "SALES").status shouldBe 202
            kit.post(listOf(Txn("R-1", "S", "40.00")), creditNote = true, sourceKey = "REFUNDS").status shouldBe 202
            kit.close()

            val sales = kit.aggregateIds(creditNote = false).single()
            val credit = kit.aggregateIds(creditNote = true).single()
            kit.lines(sales) shouldBe listOf("S=100.00")
            kit.lines(credit) shouldBe listOf("S=40.00")
            // row equality: the sales aggregate is the one the same sales give with no refund in the window
            kit.row(sales) shouldBe control.row(controlSales)
            kit.rows(
                "select credit_note::text from aggregation.aggregate where tenant_id = ? and aggregate_id = '$credit'",
            ) shouldBe
                listOf("true")
            // the same placeholders: both aggregates carry the same entity code, business-unit code and period end
            val placeholders =
                "select entity_code || '|' || business_unit_code || '|' || period_end from aggregation.aggregate " +
                    "where tenant_id = ? and aggregate_id = '%s'"
            kit.rows(placeholders.format(credit)) shouldBe kit.rows(placeholders.format(sales))
            // its own sequence number
            kit.rows("select count(distinct sequence)::text from aggregation.aggregate where tenant_id = ?") shouldBe
                listOf("2")
        }

    @Test
    fun `INV-AC-22473 refunds on S and Z give one credit-note aggregate with one line per code`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit
                .post(
                    listOf(Txn("R-1", "S", "10.00"), Txn("R-2", "S", "5.00"), Txn("R-3", "Z", "7.00")),
                    creditNote = true,
                ).status shouldBe 202
            kit.close()
            kit.handOn().size shouldBe 1

            val credit = kit.aggregateIds(creditNote = true).single()
            kit.lines(credit) shouldBe listOf("S=15.00", "Z=7.00")
            val event = kit.ready().single()
            event["aggregateId"].asString() shouldBe credit
            event["isCreditNote"].asBoolean() shouldBe true
            val codes = event["linesByInternalTaxCode"].toList().map { it["internalTaxCode"].asString() }
            codes shouldBe listOf("S", "Z")
            codes.distinct() shouldBe codes
            event["linesByInternalTaxCode"].toList().map { it["amount"].asString() } shouldBe listOf("15.00", "7.00")
            schema.validate(event).map { it.toString() }.shouldBeEmpty()
            // no line of any aggregate of the window repeats a code
            kit.rows(
                "select count(*)::text from (select aggregate_id, internal_tax_code from aggregation.aggregate_line " +
                    "where tenant_id = ? group by 1, 2 having count(*) > 1) d",
            ) shouldBe listOf("0")
        }

    @Test
    fun `INV-AC-22473 sales of several codes in one window give one line per code and a plain sales event`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { kit ->
            kit.post(listOf(Txn("S-1", "S", "10.00"), Txn("S-2", "Z", "3.00"), Txn("S-3", "S", "2.50"))).status shouldBe
                202
            kit.close()
            kit.handOn().size shouldBe 1
            val sales = kit.aggregateIds(creditNote = false).single()
            kit.lines(sales) shouldBe listOf("S=12.50", "Z=3.00")
            val event = kit.ready().single()
            event.has("isCreditNote") shouldBe false
            schema.validate(event).map { it.toString() }.shouldBeEmpty()
        }
}
