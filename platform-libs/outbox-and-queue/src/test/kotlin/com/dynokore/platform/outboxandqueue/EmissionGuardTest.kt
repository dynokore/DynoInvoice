package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
        val refused =
            assertThrows(
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
        val broken =
            "topics:\n  - topic: gst.a.b.v1\n    event_type: com.dynokore.gst.a.B.v1\n" +
                "    subscribers:\n      - x-svc\n"
        assertThrows(IllegalArgumentException::class.java) { TopicsYamlCatalogue.parse(broken) }
    }

    @Test
    fun `the catalogue parser reads the replay and request sections of the contracts topics_yaml (INV-T-3606)`() {
        val replay = checkNotNull(catalogue.replayTopic("canonical-svc", "reconciliation-svc"))
        assertEquals("gst.canonical.replay-reconciliation.v1", replay.topic)
        assertTrue(ReplayProtocol.REPLAY_COMPLETED in replay.eventTypes)
        assertTrue(TestCatalogue.CANONICAL_VERSION_CREATED in replay.eventTypes)
        val everyService = checkNotNull(catalogue.replayTopic(CatalogueEntry.EVERY_SERVICE, "evidence-svc"))
        assertEquals("gst.platform.replay-evidence.v1", everyService.topic)
        assertTrue(everyService.isProducedBy("payload-svc"))
        val request = checkNotNull(catalogue.requestTopicOf("reconciliation-svc"))
        assertEquals("gst.reconciliation.replay-requested.v1", request.topic)
        assertTrue(request.isReadBy("canonical-svc"))
        val readByCanonical = catalogue.requestTopicsReadBy("canonical-svc").map { it.topic }
        val evidence = "gst.evidence.replay-requested.v1"
        assertTrue(readByCanonical.containsAll(listOf(evidence, "gst.reconciliation.replay-requested.v1")))
        assertTrue(evidence in catalogue.requestTopicsReadBy("audit-svc").map { it.topic }, "every service")
    }

    @Test
    fun `a replay topic without the completion marker or with two subscribers is refused`() {
        val head = "replay_topics:\n  - topic: gst.a.replay-b.v1\n    event_types:\n"
        val noMarker =
            head + "      - com.dynokore.gst.a.X.v1\n" +
                "    producers:\n      - a-svc\n    subscribers:\n      - b-svc\n    content_topic: false\n"
        assertThrows(IllegalArgumentException::class.java) { TopicsYamlCatalogue.parse(noMarker) }
        val two =
            head + "      - ${ReplayProtocol.REPLAY_COMPLETED}\n" +
                "    producers:\n      - a-svc\n    subscribers:\n      - b-svc\n      - c-svc\n    content_topic: false\n"
        assertThrows(IllegalArgumentException::class.java) { TopicsYamlCatalogue.parse(two) }
    }

    @Test
    fun `ReplayRequested goes to the service's own request topic and to no other service's`() {
        val guard = EmissionGuard(catalogue, "reconciliation-svc")
        assertEquals("gst.reconciliation.replay-requested.v1", guard.require(ReplayProtocol.REPLAY_REQUESTED).topic)
        assertThrows(EmissionNotPermitted::class.java) {
            EmissionGuard(catalogue, "payload-svc").require(ReplayProtocol.REPLAY_REQUESTED)
        }
        assertThrows(EmissionNotPermitted::class.java) {
            guard.requireReplay("gst.canonical.replay-reconciliation.v1", ReplayProtocol.REPLAY_COMPLETED)
        }
    }
}
