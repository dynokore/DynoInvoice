package com.dynokore.gst.tenancy.adapters.events

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test

/**
 * The placeholder channel ExampleHappened has no producer (INV-Q-7391); a payload built from its one required property
 * `entityRef` validates, and the schema refuses one without it.
 */
class ExampleHappenedPayloadSchemaTest {
    private val schema = SchemaFixture.schema("example-happened-payload.schema.json")

    @Test
    fun `a payload with the required property validates`() {
        SchemaFixture.errors(schema, """{"entityRef":"ENT-1"}""").shouldBeEmpty()
    }

    @Test
    fun `the schema refuses a payload with the required property removed`() {
        SchemaFixture.errors(schema, "{}").shouldNotBeEmpty()
    }
}
