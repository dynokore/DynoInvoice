package com.dynokore.gst.notification.acceptance

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** Pins the staged notification contract that carries the pack alert values (INV-REQ-1080, INV-Q-20864 A). */
class PackContractTest {
    private val openapi: Map<String, Any?> =
        Yaml().load(File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText())

    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    /** The domain constant, read by reflection: acceptance has no compile dependency on the domain module (declared scope). */
    private val packAlertTrigger: String =
        Class
            .forName("com.dynokore.gst.notification.domain.alerting.PackStalenessKt")
            .getField("PACK_ALERT_TRIGGER")
            .get(null) as String

    private fun alertProperty(name: String): Map<String, Any?> =
        map(map(map(map(openapi["components"])["schemas"])["Alert"])["properties"])[name].let { map(it) }

    @Test
    fun `INV-AC-20915 the enum of Alert trigger holds PACK_NOT_ACTIVATED and the domain constant equals it`() {
        val values = alertProperty("trigger")["enum"] as List<*>
        values shouldContain "PACK_NOT_ACTIVATED"
        packAlertTrigger shouldBe "PACK_NOT_ACTIVATED"
        values shouldContain packAlertTrigger
    }

    @Test
    fun `INV-AC-20915 the enum of Alert state holds RESOLVED`() {
        (alertProperty("state")["enum"] as List<*>) shouldContain "RESOLVED"
    }

    @Test
    fun `INV-AC-20915 setAlertPolicy documents the tenant-wide reference and PACK_STALENESS`() {
        val paths = map(openapi["paths"])
        val operation =
            paths.values
                .flatMap { map(it).values }
                .filterIsInstance<Map<*, *>>()
                .map { map(it) }
                .single { it["operationId"] == "setAlertPolicy" }
        val description = operation["description"].toString()
        description shouldContain "00000000-0000-0000-0000-000000000000"
        description shouldContain "PACK_STALENESS"
    }

    @Test
    fun `INV-AC-20915 the earlier trigger values are still present`() {
        val values = alertProperty("trigger")["enum"] as List<*>
        values shouldContain "FILING_FACT_NOT_RECORDED"
        values shouldContain "BACKLOG_THRESHOLD"
    }
}
