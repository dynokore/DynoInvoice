package com.dynokore.gst.tenancy.bootstrap

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The channel PeriodRecordsChanged (INV-Q-16535 A): the payload the real composition appends is valid against the
 * `period-records-changed-payload` schema of the pinned contract bundle, and the schema rejects a payload without
 * `version`, with `version` 0 and with a period without `periodEnd`.
 */
class PeriodRecordsChangedPayloadSchemaTest {
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/tenancy/schemas/period-records-changed-payload.schema.json",
                    ),
                ) { "the payload schema is not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    private fun errors(payload: ObjectNode) = schema.validate(payload.toString(), InputFormat.JSON)

    @Test
    fun `the mapped payload validates and a payload without version, with version 0 or without periodEnd fails`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val p = PeriodEnv(ProfileEnv(context))
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val row = PeriodRecordsRows.of(entity.entityRef).single()
                val json = JsonMapper.builder().build()
                val payload = json.readTree(row) as ObjectNode
                assertThat(errors(payload)).isEmpty()
                assertThat(payload["periods"].size()).isGreaterThan(1)

                val noVersion = payload.deepCopy().also { it.remove("version") }
                assertThat(errors(noVersion)).isNotEmpty()

                val zeroVersion = payload.deepCopy().also { it.put("version", 0) }
                assertThat(errors(zeroVersion)).isNotEmpty()

                val noEnd = payload.deepCopy().also { (it["periods"][0] as ObjectNode).remove("periodEnd") }
                assertThat(errors(noEnd)).isNotEmpty()
            }
        }
    }
}
