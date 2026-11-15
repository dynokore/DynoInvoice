package com.dynokore.gst.tenancy.adapters.events

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test

/**
 * INV-AC-23029: the payload schema of the pinned contracts bundle for UnsubmittedDocumentCountMeasured, read from the
 * classpath resource that the `stageUnsubmittedCountSchema` task stages. A missing resource fails the test.
 */
class UnsubmittedCountPayloadSchemaTest {
    private val schema: Schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/submission/schemas/unsubmitted-document-count-measured-payload.schema.json",
                    ),
                ) { "the payload schema is not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    private fun payload(
        measuredAt: String? = "\"2026-11-20T10:00:00Z\"",
        total: String? = "5",
        entities: String? =
            """[{"entityRef":"E1","unsubmittedDocumentCount":3},{"entityRef":"E2","unsubmittedDocumentCount":2}]""",
    ): String =
        listOfNotNull(
            measuredAt?.let { "\"measuredAt\":$it" },
            total?.let { "\"unsubmittedDocumentCount\":$it" },
            entities?.let { "\"entities\":$it" },
        ).joinToString(",", "{", "}")

    private fun errors(json: String) = schema.validate(json, InputFormat.JSON)

    private fun item(
        ref: String?,
        count: Int,
    ) = "[{" +
        listOfNotNull(
            ref?.let { "\"entityRef\":\"$it\"" },
            "\"unsubmittedDocumentCount\":$count",
        ).joinToString(",") +
        "}]"

    @Test
    fun `INV-AC-23029 a payload with total 5 and entities E1 3 and E2 2 is valid`() {
        errors(payload()).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-23029 a payload with total 0 and no entities is valid`() {
        errors(payload(total = "0", entities = "[]")).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-23029 a payload without measuredAt is invalid`() {
        errors(payload(measuredAt = null)).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-23029 a payload with a negative total is invalid`() {
        errors(payload(total = "-1")).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-23029 a payload without entities is invalid`() {
        errors(payload(entities = null)).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-23029 an item without entityRef is invalid`() {
        errors(payload(entities = item(null, 3))).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-23029 an item with count 0 is invalid`() {
        errors(payload(entities = item("E1", 0))).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-23029 an item with an empty entityRef is invalid`() {
        errors(payload(entities = item("", 3))).shouldNotBeEmpty()
    }
}
