package com.dynokore.gst.tenancy.adapters.events

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** The replay and request topics of the staged event-log topics.yaml are in tenancy-svc's catalogue (INV-AC-25009). */
class TenancyEventCatalogueReplayTopicsTest {
    private val topicsText =
        checkNotNull(javaClass.classLoader.getResourceAsStream(TenancyEventCatalogue.TOPICS_RESOURCE)) {
            "${TenancyEventCatalogue.TOPICS_RESOURCE} is not on the classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
    private val asyncApi =
        checkNotNull(javaClass.classLoader.getResourceAsStream(TenancyEventCatalogue.ASYNCAPI_RESOURCE)) {
            "${TenancyEventCatalogue.ASYNCAPI_RESOURCE} is not on the classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Suppress("UNCHECKED_CAST")
    private fun section(name: String): List<Map<String, Any?>> =
        (Yaml().load<Map<String, Any?>>(topicsText)[name] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    private fun names(
        entry: Map<String, Any?>,
        key: String,
    ) = (entry[key] as List<String>).toSet()

    private fun requestTopic(
        topic: String,
        subscribers: List<String>,
    ) = "  - topic: $topic\n    event_types:\n      - com.dynokore.gst.platform.ReplayRequested.v1\n" +
        "    content_topic: false\n    record_topic: false\n    retained_while_resolvable: false\n" +
        "    producers:\n      - some-svc\n    subscribers:\n" + subscribers.joinToString("") { "      - $it\n" }

    @Test
    fun `INV-AC-25009 requestTopicsReadBy of tenancy-svc equals the request topics of tenancy-svc or every service`() {
        val expected =
            section("replay_request_topics")
                .filter { e -> names(e, "subscribers").any { it == "tenancy-svc" || it == "every service" } }
                .map { it["topic"] as String }
                .toSet()
        val actual = TenancyEventCatalogue.load().requestTopicsReadBy("tenancy-svc").map { it.topic }
        (expected.isNotEmpty()) shouldBe true
        actual.toSet() shouldBe expected
        actual.size shouldBe expected.size
    }

    @Test
    fun `INV-AC-25009 the replay topic for access-svc is listed with the event types of the file`() {
        val file = section("replay_topics").single { it["topic"] == "gst.tenancy.replay-access.v1" }
        val listed = TenancyEventCatalogue.load().replayTopicNamed("gst.tenancy.replay-access.v1")
        checkNotNull(listed)
        listed.eventTypes shouldBe names(file, "event_types")
        listed.producer shouldBe "tenancy-svc"
        listed.subscriber shouldBe "access-svc"
    }

    @Test
    fun `INV-AC-25009 the replay topics listed are exactly those the file names with tenancy-svc as producer`() {
        val expected =
            section("replay_topics")
                .filter { names(it, "producers") == setOf("tenancy-svc") }
                .map { it["topic"] as String }
                .toSet()
        (expected.isNotEmpty()) shouldBe true
        TenancyEventCatalogue
            .load()
            .replayTopics()
            .map { it.topic }
            .toSet() shouldBe expected
    }

    @Test
    fun `INV-AC-25009 a topic with only a look-alike or another service as subscriber is not returned`() {
        val text =
            "replay_request_topics:\n" +
                requestTopic("gst.a.replay-requested.v1", listOf("tenancy-svc-x")) +
                requestTopic("gst.b.replay-requested.v1", listOf("access-svc")) +
                requestTopic("gst.c.replay-requested.v1", listOf("Tenancy-svc")) +
                requestTopic("gst.d.replay-requested.v1", listOf("tenancy-svc")) +
                requestTopic("gst.e.replay-requested.v1", listOf("every service")) +
                requestTopic("gst.f.replay-requested.v1", listOf("every service-x"))
        // the one-consumer-per-topic rule of EventCatalogue needs distinct producers
        val distinct =
            text.split("  - topic: ").drop(1).mapIndexed { i, b -> "  - topic: " + b.replace("some-svc", "s$i-svc") }
        val parsed = TenancyEventCatalogue.build(asyncApi, "replay_request_topics:\n" + distinct.joinToString(""))
        parsed.requestTopicsReadBy("tenancy-svc").map { it.topic } shouldBe
            listOf("gst.d.replay-requested.v1", "gst.e.replay-requested.v1")
    }

    @Test
    fun `INV-AC-25009 every entry listed before the topics were added is still listed unchanged`() {
        val before = TenancyEventCatalogue.build(asyncApi).entries()
        val after = TenancyEventCatalogue.load().entries()
        (before.isNotEmpty()) shouldBe true
        after.toList() shouldContainAll before.toList()
        after.size shouldBe before.size
    }

    @Test
    fun `INV-AC-25009 the catalogue source names no replay or request topic of the file`() {
        val source =
            File("src/main/kotlin/com/dynokore/gst/tenancy/adapters/events/TenancyEventCatalogue.kt").readText()
        val topics = (section("replay_topics") + section("replay_request_topics")).map { it["topic"] as String }
        (topics.isNotEmpty()) shouldBe true
        topics.filter { source.contains(it) } shouldBe emptyList()
        Regex("""gst\.[a-z-]+\.replay-[a-z-]+\.v\d+""").findAll(source).toList() shouldBe emptyList()
    }
}
