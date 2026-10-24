package com.dynokore.gst.tenancy.bootstrap

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The channel FilingFrequencyChanged (INV-Q-7391, INV-Q-7391's owner delegation to INV-T-4929): the event the
 * confirmation publishes is valid against the contract schema, exactly one follows the confirmation and none
 * precedes it.
 */
class FilingFrequencyChangedPayloadSchemaTest {
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/tenancy/schemas/filing-frequency-changed-payload.schema.json",
                    ),
                ) { "the payload schema is not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    @Test
    fun `exactly one event follows the confirmation, none precedes it, and the payload validates`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val p = PeriodEnv(ProfileEnv(context))
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                assertThat(p.frequencyEvents()).isEmpty()
                val proposal = p.change(entity, Freq.MONTHLY, "2026-02-01").body!!.confirmationPeriod!!
                assertThat(p.frequencyEvents()).isEmpty()
                p.confirm(entity, proposal.periodRef, "2026-01-01", "2026-01-31", Kind.TRANSITIONAL)
                p.confirm(entity, proposal.periodRef, "2026-01-01", "2026-01-31", Kind.TRANSITIONAL)
                val event = p.frequencyEvents().single()
                val payload = JsonMapper.builder().build().readTree(event.dataJson) as ObjectNode
                assertThat(schema.validate(payload.toString(), InputFormat.JSON)).isEmpty()
                assertThat(payload["entityRef"].asString()).isEqualTo(entity.entityRef)
                assertThat(payload["oldFrequency"].asString()).isEqualTo("QUARTERLY")
                assertThat(payload["newFrequency"].asString()).isEqualTo("MONTHLY")
                val february = p.records(entity).single { it.start == day("2026-02-01") }
                assertThat(payload["affectedSpan"]["fromPeriod"].asString())
                    .isEqualTo(february.periodRef)
                    .isNotEqualTo("2026-02-01")
                assertThat(payload["affectedSpan"].has("toPeriod")).isFalse()
                payload.remove("affectedSpan")
                assertThat(schema.validate(payload.toString(), InputFormat.JSON)).isNotEmpty()
            }
        }
    }
}
