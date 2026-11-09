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

    private val linkType = SignedLinkRequestedHandler.TYPE
    private val linkTopic = SignedLinkRequestedHandler.TOPIC

    private fun topics(
        topic: String = linkTopic,
        type: String = linkType,
        subscribers: String = "access-svc",
        producers: String? = "    producers:\n      - masterdata-svc\n",
    ) =
        "topics:\n  - topic: $topic\n    event_type: $type\n${producers.orEmpty()}    subscribers:\n      - $subscribers\n"

    @Test
    fun `the committed topics file gives exactly masterdata-svc for SignedLinkRequested`() {
        assertEquals(setOf("masterdata-svc"), AccessEventCatalogue.signedLinkProducers())
    }

    @Test
    fun `a valid topics document is read`() {
        assertEquals(setOf("masterdata-svc"), AccessEventCatalogue.signedLinkProducers(topics()))
    }

    @Test
    fun `each topics document without a usable entry throws`() {
        val bad =
            listOf(
                "topics: []\n",
                topics(type = "$linkType.v2"),
                topics(type = linkType.replace(".v1", ".v2")),
                topics(topic = "gst.masterdata.other.v1"),
                topics(subscribers = "payload-svc"),
                topics(producers = "    producers: []\n"),
                topics(producers = null),
                "other: 1\n",
            )
        for (d in bad) assertThrows(IllegalStateException::class.java) { AccessEventCatalogue.signedLinkProducers(d) }
    }

    @Test
    fun `INV-AC-18218 the catalogue lists both signed-link events, produced by access-svc`() {
        val catalogue = AccessEventCatalogue.load()
        listOf(
            SignedLinkEventOutbox.ISSUED_TYPE to "gst.access.signed-link-issued.v1",
            SignedLinkEventOutbox.INVALIDATED_TYPE to "gst.access.signed-link-invalidated.v1",
        ).forEach { (type, topic) ->
            val entry = checkNotNull(catalogue.find(type)) { "$type is not in the catalogue" }
            assertEquals(topic, entry.topic)
            assertEquals(setOf(AccessEventCatalogue.SERVICE), entry.producers)
        }
        assertEquals(null, catalogue.find("com.dynokore.gst.access.SignedLinkInvalidated.v2"))
    }
}
