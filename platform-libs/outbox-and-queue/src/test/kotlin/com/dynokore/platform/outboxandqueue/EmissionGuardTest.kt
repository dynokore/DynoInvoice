package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** INV-AC-1869's catalogue rule without a database: emission is allowed only for the catalogued service. */
class EmissionGuardTest {
    private val catalogue = TestCatalogue.load()

    @Test
    fun `the catalogued producer may emit and any other service may not`() {
        assertEquals(
            "gst.payload.payload-ready.v1",
            EmissionGuard(catalogue, "payload-svc").require(TestCatalogue.PAYLOAD_READY).topic,
        )
        val refused = assertThrows(
            EmissionNotPermitted::class.java,
        ) { EmissionGuard(catalogue, "submission-svc").require(TestCatalogue.PAYLOAD_READY) }
        assertEquals("submission-svc", refused.service)
        assertEquals(TestCatalogue.PAYLOAD_READY, refused.eventType)
    }

    @Test
    fun `a type missing from the catalogue is refused`() {
        assertThrows(
            EmissionNotPermitted::class.java,
        ) { EmissionGuard(catalogue, "payload-svc").require("com.dynokore.gst.payload.Unknown.v1") }
    }

    @Test
    fun `a platform event catalogued to every service may be emitted by any service`() {
        listOf("payload-svc", "submission-svc", "console-bff").forEach {
            assertEquals(
                TestCatalogue.AUDIT_ENTRY_RECORDED,
                EmissionGuard(catalogue, it).require(TestCatalogue.AUDIT_ENTRY_RECORDED).eventType,
            )
        }
    }

    @Test
    fun `the catalogue parser reads producers and rejects an entry without one`() {
        assertEquals(setOf("payload-svc"), checkNotNull(catalogue.find(TestCatalogue.PAYLOAD_READY)).producers)
        val broken = "topics:\n  - topic: gst.a.b.v1\n    event_type: com.dynokore.gst.a.B.v1\n" +
            "    subscribers:\n      - x-svc\n"
        assertThrows(IllegalArgumentException::class.java) { TopicsYamlCatalogue.parse(broken) }
    }
}
