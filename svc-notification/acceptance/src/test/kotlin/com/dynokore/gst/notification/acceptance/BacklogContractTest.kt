package com.dynokore.gst.notification.acceptance

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** Pins the staged notification contract that carries the backlog alert values (INV-REQ-1370). */
class BacklogContractTest {
    private val openapi: Map<String, Any?> =
        Yaml().load(File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText())

    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun schema(name: String): Map<String, Any?> = map(map(map(openapi["components"])["schemas"])[name])

    @Test
    fun `INV-AC-20822 the enum of Alert trigger holds BACKLOG_THRESHOLD and still FILING_FACT_NOT_RECORDED`() {
        val trigger = map(map(schema("Alert")["properties"])["trigger"])
        val values = trigger["enum"] as List<*>
        values shouldContain "BACKLOG_THRESHOLD"
        values shouldContain "FILING_FACT_NOT_RECORDED"
    }

    @Test
    fun `INV-AC-20822 AlertPolicy backlogThresholds has exactly the four threshold members`() {
        val policy = map(schema("AlertPolicy")["properties"])
        policy.keys shouldContain "backlogThresholds"
        policy.keys.contains("backlogThreshold") shouldBe false
        val thresholds = map(policy["backlogThresholds"])
        val members = resolve(thresholds)
        members shouldNotBe null
        members!!.keys shouldBe
            setOf("maxQueueDepth", "maxOldestUnsentAgeSeconds", "maxFailureRatio", "maxUnacknowledgedCount")
    }

    @Test
    fun `INV-AC-20822 the contract info version is not lower than 1_15_0`() {
        val version = map(openapi["info"])["version"].toString()
        val parts = version.substringBefore('-').substringBefore('+').split('.').map { it.toInt() }
        val floor = listOf(1, 15, 0)
        val lower =
            (0..2).map { parts[it].compareTo(floor[it]) }.firstOrNull { it != 0 }?.let { it < 0 } ?: false
        lower shouldBe false
    }

    /** The properties of a schema node, following one level of a local `$ref`. */
    private fun resolve(node: Map<String, Any?>): Map<String, Any?>? {
        val ref = node["\$ref"] as String?
        val target = if (ref == null) node else schema(ref.substringAfterLast('/'))
        @Suppress("UNCHECKED_CAST")
        return target["properties"] as Map<String, Any?>?
    }
}
