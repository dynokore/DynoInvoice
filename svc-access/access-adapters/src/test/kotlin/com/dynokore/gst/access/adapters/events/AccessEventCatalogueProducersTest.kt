package com.dynokore.gst.access.adapters.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AccessEventCatalogueProducersTest {
    private val type = "com.dynokore.gst.platform.BreakGlassActionReported.v1"

    private fun doc(
        root: String,
        components: String = "",
    ) = "$root\nchannels: {}\ncomponents:\n  messages: {}\n$components"

    private val entry = "  - event_type: $type\n    topic: t\n    producers:\n      - tenancy-svc\n"

    @Test
    fun `the committed document names tenancy-svc as the single producer`() {
        assertEquals(setOf("tenancy-svc"), AccessEventCatalogue.producersOf(type))
    }

    @Test
    fun `the declaration does not change the events access produces`() {
        val text =
            AccessEventCatalogue::class.java.classLoader
                .getResourceAsStream(AccessEventCatalogue.ASYNCAPI_RESOURCE)!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(false, AccessEventCatalogue.producedBy(text).any { it.eventType == type })
    }

    @Test
    fun `a valid document is read`() {
        assertEquals(setOf("tenancy-svc"), AccessEventCatalogue.producersOf(type, doc("x-consumed-events:\n$entry")))
    }

    @Test
    fun `each malformed document throws`() {
        val bad =
            listOf(
                doc("openapi: x"),
                doc("x-consumed-event:\n$entry"),
                doc("x-other: 1", "  x-consumed-events:\n$entry"),
                doc("x-consumed-events:\n${entry.replace(type, "com.dynokore.gst.platform.Other.v1")}"),
                doc("x-consumed-events:\n  - event_type: $type\n    topic: t\n    producers: []\n"),
            )
        for (d in bad) assertThrows(IllegalStateException::class.java) { AccessEventCatalogue.producersOf(type, d) }
    }
}
