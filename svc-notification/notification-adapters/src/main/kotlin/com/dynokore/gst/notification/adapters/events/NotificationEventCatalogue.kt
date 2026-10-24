package com.dynokore.gst.notification.adapters.events

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.ReplicaFeeds
import org.yaml.snakeyaml.Yaml

/**
 * The event catalogue of notification-svc, built only from published artefacts: the events it produces are the messages
 * of the channels of its asyncapi document (platform-contracts notification/asyncapi.yaml, staged into
 * notification-contract); the events it consumes are product-runtime's replica feeds; the platform events are
 * product-runtime's catalogue. The emission guard of outbox-and-queue then refuses any type this catalogue does not
 * list for notification-svc (INV-Q-9739 A).
 */
object NotificationEventCatalogue {
    const val SERVICE = "notification-svc"
    const val ASYNCAPI_RESOURCE = "gst/contracts/notification/asyncapi.yaml"

    /** The scaffold channel the contract keeps only because its removal would be a breaking change. */
    const val PLACEHOLDER_CHANNEL = "notificationExampleHappened"

    fun load(): EventCatalogue {
        val text =
            checkNotNull(NotificationEventCatalogue::class.java.classLoader.getResourceAsStream(ASYNCAPI_RESOURCE)) {
                "$ASYNCAPI_RESOURCE is not on the classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        return build(text)
    }

    fun build(asyncApi: String): EventCatalogue {
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
        return EventCatalogue(own + consumed + platform)
    }

    /** A channel message is either inline or a `$ref` to `#/components/messages/<name>`. */
    private fun resolve(
        message: Map<String, Any?>,
        components: Map<String, Map<String, Any?>>,
    ): Map<String, Any?> {
        val ref = message["\$ref"] as String? ?: return message
        require(ref.startsWith(COMPONENT_PREFIX)) { "unsupported message reference $ref" }
        val name = ref.removePrefix(COMPONENT_PREFIX)
        return checkNotNull(components[name]) { "message $name is not defined in components" }
    }

    private const val COMPONENT_PREFIX = "#/components/messages/"

    @Suppress("UNCHECKED_CAST")
    fun producedBy(asyncApi: String): List<CatalogueEntry> {
        val document = Yaml().load<Map<String, Any?>>(asyncApi)
        val channels = document["channels"] as Map<String, Map<String, Any?>>
        val components = (document["components"] as Map<String, Any?>)["messages"] as Map<String, Map<String, Any?>>
        return channels
            .filterKeys { it != PLACEHOLDER_CHANNEL }
            .map { (_, channel) ->
                val topic = channel["address"] as String
                val messages = channel["messages"] as Map<String, Map<String, Any?>>
                val message = messages.values.single().let { resolve(it, components) }
                CatalogueEntry(message["name"] as String, topic, setOf(SERVICE), contentTopic = false)
            }
    }
}
