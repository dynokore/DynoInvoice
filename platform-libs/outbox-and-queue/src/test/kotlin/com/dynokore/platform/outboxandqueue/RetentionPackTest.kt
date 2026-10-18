package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

/** The pack artefact retention and the `retained_while_resolvable` marker (INV-REQ-1210, INV-Q-6281; INV-T-6291). */
class RetentionPackTest {
    @Test
    fun `the pack retention defaults to 1827 days and 1826 days is refused`() {
        assertEquals(Duration.ofDays(1827), Retention().packArtefactRetention)
        assertEquals(Duration.ofDays(1827), Retention(Duration.ofDays(10), Duration.ofDays(30)).packArtefactRetention)
        assertThrows(IllegalArgumentException::class.java) { Retention(packArtefactRetention = Duration.ofDays(1826)) }
        assertThrows(IllegalArgumentException::class.java) {
            Retention(packArtefactRetention = Duration.ofDays(1827).minusSeconds(1))
        }
        assertEquals(
            Duration.ofDays(1827),
            Retention(packArtefactRetention = Duration.ofDays(1827)).packArtefactRetention,
        )
        assertEquals(
            Duration.ofDays(3650),
            Retention(packArtefactRetention = Duration.ofDays(3650)).packArtefactRetention,
        )
    }

    @Test
    fun `30 days, the default of the other two periods, is refused for the pack period`() {
        assertThrows(IllegalArgumentException::class.java) { Retention(packArtefactRetention = Retention.DEFAULT) }
    }

    @Test
    fun `the inbox retention does not depend on the pack retention`() {
        assertEquals(
            Retention().inboxRetention,
            Retention(packArtefactRetention = Duration.ofDays(3650)).inboxRetention,
        )
        assertEquals(Duration.ofDays(31), Retention().inboxRetention)
    }

    @Test
    fun `the unrelayed-event settings ignore the pack retention`() {
        val wide = Retention(packArtefactRetention = Duration.ofDays(3650))
        assertEquals(UnrelayedEventSettings.forRetention(Retention()), UnrelayedEventSettings.forRetention(wide))
    }

    private val event =
        """
        topics:
          - topic: gst.a.b.v1
            event_type: x.B.v1
            retained_while_resolvable: true
            producers:
              - a-svc
          - topic: gst.a.c.v1
            event_type: x.C.v1
            retained_while_resolvable: false
            producers:
              - a-svc
          - topic: gst.a.d.v1
            event_type: x.D.v1
            producers:
              - a-svc
        """.trimIndent()

    @Test
    fun `the marker is read on an event topic, and an entry without it gets null`() {
        val markers = TopicsYamlCatalogue.parse(event).entries().associate { it.topic to it.retainedWhileResolvable }
        assertEquals(mapOf("gst.a.b.v1" to true, "gst.a.c.v1" to false, "gst.a.d.v1" to null), markers)
    }

    @Test
    fun `the marker is read on a replay topic, which is refused without it, and a request topic has none`() {
        val replay =
            """
            replay_topics:
              - topic: gst.platform.replay-b-svc.v1
                event_types:
                  - ${ReplayProtocol.REPLAY_COMPLETED}
                content_topic: false
                retained_while_resolvable: true
                producers:
                  - every service
                subscribers:
                  - b-svc
            replay_request_topics:
              - topic: gst.b-svc.replay-requested.v1
                event_types:
                  - ${ReplayProtocol.REPLAY_REQUESTED}
                producers:
                  - b-svc
                subscribers:
                  - a-svc
            """.trimIndent()
        val catalogue = TopicsYamlCatalogue.parse(replay)
        assertEquals(true, catalogue.replayTopics().single().retainedWhileResolvable)
        assertEquals(1, catalogue.requestTopics().size)
        val refused =
            assertThrows(IllegalArgumentException::class.java) {
                TopicsYamlCatalogue.parse(replay.replace("    retained_while_resolvable: true\n", ""))
            }
        assertEquals(true, refused.message?.contains("gst.platform.replay-b-svc.v1"))
    }

    @Test
    fun `a value other than true or false is refused naming the topic`() {
        val refused =
            assertThrows(IllegalArgumentException::class.java) {
                TopicsYamlCatalogue.parse(
                    event.replace("retained_while_resolvable: true", "retained_while_resolvable: yes"),
                )
            }
        assertEquals(true, refused.message?.contains("gst.a.b.v1"))
    }

    @Test
    fun `the look-alike key retained_while_resolveable is not read`() {
        val text = event.replace("retained_while_resolvable: true", "retained_while_resolveable: true")
        assertNull(
            TopicsYamlCatalogue
                .parse(text)
                .entries()
                .first { it.topic == "gst.a.b.v1" }
                .retainedWhileResolvable,
        )
    }

    @Test
    fun `the excerpt marks RulePackStaged and no other event topic`() {
        val marked = TestCatalogue.load().entries().filter { it.retainedWhileResolvable == true }
        assertEquals(listOf("gst.rules.rule-pack-staged.v1"), marked.map { it.topic })
    }
}
