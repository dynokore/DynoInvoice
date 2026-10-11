package com.dynokore.platform.outboxandqueue

/**
 * Reads the event catalogue from platform-contracts' generated `event-log/topics.yaml` (one
 * entry per topic: `topic`, `event_type`, `producers`, `subscribers`, and from INV-T-3386 `content_topic: true|false`,
 * INV-REQ-1205 b4; an entry without it gets [CatalogueEntry.contentTopic] `null`). The file is machine
 * generated in one fixed shape, so a line reader is enough and keeps a YAML library (a new STK
 * entry, CS-44) out of every service. An entry without a topic, type or producer is refused.
 */
object TopicsYamlCatalogue {
    /** Parses [text] into an [EventCatalogue]; throws [IllegalArgumentException] on a malformed entry. */
    fun parse(text: String): EventCatalogue {
        val entries = mutableListOf<CatalogueEntry>()
        var topic: String? = null
        var type: String? = null
        var producers = mutableSetOf<String>()
        var content: Boolean? = null
        var inProducers = false

        fun flush() {
            val t = topic ?: return
            val eventType = requireNotNull(type) { "topic $t has no event_type" }
            entries += CatalogueEntry(eventType, t, producers.toSet(), content)
            require(producers.isNotEmpty()) { "topic $t has no producer" }
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("- topic:") -> {
                    flush()
                    topic = line.removePrefix("- topic:").trim()
                    type = null
                    producers = mutableSetOf()
                    content = null
                    inProducers = false
                }

                line.startsWith("content_topic:") -> {
                    val value = line.removePrefix("content_topic:").trim()
                    content =
                        requireNotNull(value.toBooleanStrictOrNull()) {
                            "topic $topic has content_topic '$value', expected true or false"
                        }
                    inProducers = false
                }

                line.startsWith("event_type:") -> type = line.removePrefix("event_type:").trim()

                line == "producers:" -> inProducers = true

                line.endsWith(":") -> inProducers = false

                line.startsWith("- ") && inProducers -> producers += line.removePrefix("- ").trim()
            }
        }
        flush()
        return EventCatalogue(entries)
    }
}
