package com.dynokore.gst.notification.acceptance

import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

class AlertTriggerContractTest {
    @Test
    fun `INV-AC-10443 the contract enum of Alert trigger holds FILING_FACT_NOT_RECORDED`() {
        val file = File(System.getProperty("gst.contract.dir"), "openapi.yaml")
        val openapi: Map<String, Any?> = Yaml().load(file.readText())

        fun map(value: Any?): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }
        val alert = map(map(map(openapi["components"])["schemas"])["Alert"])
        val trigger = map(map(alert["properties"])["trigger"])
        (trigger["enum"] as List<*>) shouldContain "FILING_FACT_NOT_RECORDED"
    }
}
