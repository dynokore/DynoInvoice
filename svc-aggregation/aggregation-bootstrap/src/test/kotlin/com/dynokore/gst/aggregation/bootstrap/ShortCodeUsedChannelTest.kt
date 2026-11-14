package com.dynokore.gst.aggregation.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * INV-Q-11266 A (slice 1 of 2): aggregation-svc declares the ShortCodeUsed channel. The catalogue entry the emission
 * guard enforces matches, by equality, the type and topic of the channel `aggregationShortCodeUsed` of the pinned,
 * staged AsyncAPI contract.
 */
class ShortCodeUsedChannelTest {
    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private val asyncapi: Map<String, Any?> =
        Yaml().load(File(System.getProperty("gst.contract.dir"), "asyncapi.yaml").readText())

    private val channel = map(map(asyncapi["channels"])["aggregationShortCodeUsed"])

    @Test
    fun `the catalogue entry of ShortCodeUsed is on the channel topic, produced by aggregation-svc only`() {
        val entry = AggregationEvents.entries.single { it.eventType == "com.dynokore.gst.aggregation.ShortCodeUsed.v1" }
        entry.topic shouldBe "gst.aggregation.short-code-used.v1"
        entry.producers shouldBe setOf("aggregation-svc")
        entry.contentTopic shouldBe false
    }

    @Test
    fun `the topic and the type are those of the channel aggregationShortCodeUsed of the staged contract`() {
        val message = map(channel["messages"]).keys.single()
        val entry = AggregationEvents.entries.single { it.topic == channel["address"] }
        channel["address"] shouldBe "gst.aggregation.short-code-used.v1"
        entry.eventType shouldBe "com.dynokore.gst.aggregation.$message.v1"
        AggregationEvents.entries
            .map { it.eventType }
            .toSet()
            .size shouldBe 3
    }
}
