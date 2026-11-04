package com.dynokore.gst.tenancy.bootstrap

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * The channel PeriodDueDateChanged (INV-Q-7391, delegated to INV-T-4930): the payload of a recorded extension is valid
 * against the contract schema, exactly one event follows it, and the same payload without `cause` is
 * refused by the schema.
 */
class PeriodDueDateChangedPayloadSchemaTest {
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/tenancy/schemas/period-due-date-changed-payload.schema.json",
                    ),
                ) { "the payload schema is not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    @Test
    fun `the payload validates, and the same payload without cause is refused`() {
        runPeriods { period, entity ->
            period.insertRecord(entity, record("2026Q1", "2026-01-01", "2026-03-31", "2026-04-30"))
            period.actAs(entity, "tax.approver")
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).isEmpty()
            period.extend(entity, "2026Q1", extension("2026-05-14"))
            val payload = period.events(PERIOD_DUE_DATE_CHANGED).single() as ObjectNode
            assertThat(schema.validate(payload.toString(), InputFormat.JSON)).isEmpty()
            val required = listOf("entityRef", "period", "oldFilingDueDate", "newFilingDueDate", "cause")
            assertThat(payload.propertyNames().asSequence().toSet()).containsAll(required)
            // probe: without the member the schema refuses; so it does without any other required member
            required.forEach { member ->
                val copy = payload.deepCopy()
                copy.remove(member)
                assertThat(
                    schema.validate(copy.toString(), InputFormat.JSON),
                ).describedAs("without $member").isNotEmpty()
            }
            val without = payload.deepCopy()
            without.remove("cause")
            assertThat(schema.validate(without.toString(), InputFormat.JSON)).isNotEmpty()
        }
    }
}
