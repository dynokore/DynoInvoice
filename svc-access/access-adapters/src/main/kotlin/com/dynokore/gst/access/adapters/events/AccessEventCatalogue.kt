package com.dynokore.gst.access.adapters.events

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.ReplicaFeeds
import org.yaml.snakeyaml.Yaml

/**
 * The event catalogue of access-svc, built only from published artefacts: the events access produces are the messages
 * of
 * the channels of its asyncapi document (platform-contracts access/asyncapi.yaml, staged into access-contract); the
 * events it consumes are product-runtime's replica feeds; the platform events are product-runtime's catalogue. The
 * emission guard of outbox-and-queue then refuses any type this catalogue does not list for access-svc.
 */
object AccessEventCatalogue {
    const val SERVICE = "access-svc"
    const val ASYNCAPI_RESOURCE = "gst/contracts/access/asyncapi.yaml"
    const val TOPICS_RESOURCE = "gst/contracts/event-log/topics.yaml"

    /**
     * The scaffold channel the contract keeps only because its removal would be a breaking change (asyncapi header).
     */
    private val PLACEHOLDER_CHANNELS = setOf("accessExampleHappened")

    fun load(): EventCatalogue = build(loadText())

    fun build(
        asyncApi: String,
        linkProducers: Set<String> = signedLinkProducers(),
    ): EventCatalogue {
        val own = producedBy(asyncApi)
        val ownTypes = own.map { it.eventType }.toSet()
        val consumed =
            ReplicaFeeds.ALL
                .filter { it.eventType !in ownTypes }
                .map {
                    CatalogueEntry(
                        it.eventType,
                        it.topic,
                        setOf(it.fallbackService ?: CatalogueEntry.EVERY_SERVICE),
                        contentTopic = false,
                    )
                }
        val seen = (ownTypes + consumed.map { it.eventType })
        val platform =
            PlatformEventCatalogue.ALL
                .filter { it.topic != null && it.eventType !in seen }
                .map {
                    CatalogueEntry(
                        it.eventType,
                        checkNotNull(it.topic),
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        contentTopic = false,
                    )
                }
        // SignedLinkRequested is no replica feed and no platform event: its producers are those the event-log topics
        // file of the pinned bundle lists for access-svc
        val link =
            CatalogueEntry(
                SignedLinkRequestedHandler.TYPE,
                SignedLinkRequestedHandler.TOPIC,
                linkProducers,
                contentTopic = false,
            )
        return EventCatalogue(own + consumed + platform + link)
    }

    /** A channel message is either inline or a `$ref` to `#/components/messages/<name>`. */
    private fun resolve(
        message: Map<String, Any?>,
        components: Map<String, Map<String, Any?>>,
    ): Map<String, Any?> {
        val ref = message["\$ref"] as String? ?: return message
        val name = ref.removePrefix(COMPONENT_PREFIX)
        require(ref.startsWith(COMPONENT_PREFIX)) { "unsupported message reference $ref" }
        return checkNotNull(components[name]) { "message $name is not defined in components" }
    }

    private const val COMPONENT_PREFIX = "#/components/messages/"

    @Suppress("UNCHECKED_CAST")
    fun producedBy(asyncApi: String): List<CatalogueEntry> {
        val document = Yaml().load<Map<String, Any?>>(asyncApi)
        val channels = document["channels"] as Map<String, Map<String, Any?>>
        val components = (document["components"] as Map<String, Any?>)["messages"] as Map<String, Map<String, Any?>>
        return channels
            .filterKeys { it !in PLACEHOLDER_CHANNELS }
            .map { (_, channel) ->
                val topic = channel["address"] as String
                val messages = channel["messages"] as Map<String, Map<String, Any?>>
                val message = messages.values.single().let { resolve(it, components) }
                CatalogueEntry(message["name"] as String, topic, setOf(SERVICE), contentTopic = false)
            }
    }

    /**
     * The services that may produce [eventType] for access-svc, from the root extension `x-consumed-events` of the
     * contract. Never empty: a missing key, entry or list is a start failure, so a handler never runs without
     * producers.
     */
    @Suppress("UNCHECKED_CAST")
    fun producersOf(
        eventType: String,
        asyncApi: String = loadText(),
    ): Set<String> {
        val document = Yaml().load<Map<String, Any?>>(asyncApi)
        val declared =
            checkNotNull(document[CONSUMED_EVENTS_KEY] as? List<Map<String, Any?>>) {
                "$ASYNCAPI_RESOURCE has no root key $CONSUMED_EVENTS_KEY"
            }
        val entry =
            checkNotNull(declared.firstOrNull { it["event_type"] == eventType }) {
                "$CONSUMED_EVENTS_KEY has no entry for $eventType"
            }
        val producers = (entry["producers"] as? List<Any?>).orEmpty().map { it as String }.toSet()
        check(producers.isNotEmpty()) { "$CONSUMED_EVENTS_KEY lists no producers for $eventType" }
        return producers
    }

    /**
     * The producers of SignedLinkRequested, from `event-log/topics.yaml` of the pinned bundle (INV-Q-17900): the entry
     * of the topic and type that lists access-svc among its subscribers. A missing entry, a missing subscriber or an
     * empty or missing `producers` list is an [IllegalStateException]; the set is never empty or a default.
     */
    @Suppress("UNCHECKED_CAST")
    fun signedLinkProducers(topics: String = loadResource(TOPICS_RESOURCE)): Set<String> {
        val document = Yaml().load<Any?>(topics) as? Map<String, Any?>
        val entries = checkNotNull(document?.get("topics") as? List<Any?>) { "$TOPICS_RESOURCE has no topics list" }
        val entry =
            checkNotNull(
                entries.filterIsInstance<Map<String, Any?>>().firstOrNull {
                    it["topic"] == SignedLinkRequestedHandler.TOPIC &&
                        it["event_type"] == SignedLinkRequestedHandler.TYPE &&
                        (it["subscribers"] as? List<Any?>).orEmpty().contains(SERVICE)
                },
            ) { "$TOPICS_RESOURCE has no entry of ${SignedLinkRequestedHandler.TYPE} that access-svc subscribes to" }
        val producers = (entry["producers"] as? List<Any?>).orEmpty().map { it.toString() }.toSet()
        check(producers.isNotEmpty()) { "$TOPICS_RESOURCE lists no producers for ${SignedLinkRequestedHandler.TYPE}" }
        return producers
    }

    private const val CONSUMED_EVENTS_KEY = "x-consumed-events"

    private fun loadText(): String = loadResource(ASYNCAPI_RESOURCE)

    private fun loadResource(name: String): String =
        checkNotNull(AccessEventCatalogue::class.java.classLoader.getResourceAsStream(name)) {
            "$name is not on the classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
}
