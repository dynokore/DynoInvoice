package com.dynokore.platform.outboxandqueue

/**
 * Reads the event catalogue from platform-contracts' generated `event-log/topics.yaml`. Three sections:
 * - `topics`: one entry per event topic: `topic`, `event_type`, `producers`, `subscribers`, from INV-T-3386
 *   `content_topic: true|false` (INV-REQ-1205 b4; an entry without it gets [CatalogueEntry.contentTopic] `null`) and
 *   from INV-T-3606 `record_topic` ([CatalogueEntry.recordTopic], INV-Q-3809);
 * - `replay_topics` (INV-T-3606): one per (producer, subscriber) pair, with `event_types`, exactly one producer and
 *   one subscriber ([ReplayTopic]);
 * - `replay_request_topics` (INV-T-3606): one per consumer, with `event_types`, the consumer as its one producer and
 *   the producers it reads from as subscribers ([ReplayRequestTopic]).
 *
 * The file is machine generated in one fixed shape, so a line reader is enough and keeps a YAML library (a new STK
 * entry, CS-44) out of every service. An event topic without a topic, type or producer is refused, as is a replay or
 * request topic without its one producer, its one subscriber (replay) or its event types.
 */
object TopicsYamlCatalogue {
    private enum class Section { TOPICS, REPLAY, REQUEST }

    private class Entry(
        val section: Section,
        val topic: String,
    ) {
        var type: String? = null
        val types = mutableSetOf<String>()
        val producers = mutableSetOf<String>()
        val subscribers = mutableSetOf<String>()
        var content: Boolean? = null
        var record = false
    }

    /** Parses [text] into an [EventCatalogue]; throws [IllegalArgumentException] on a malformed entry. */
    fun parse(text: String): EventCatalogue {
        val entries = mutableListOf<Entry>()
        var section: Section? = null
        var entry: Entry? = null
        var list: MutableSet<String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!raw.startsWith(" ") && !raw.startsWith("-")) {
                section =
                    when (line) {
                        "topics:" -> Section.TOPICS
                        "replay_topics:" -> Section.REPLAY
                        "replay_request_topics:" -> Section.REQUEST
                        else -> null
                    }
                entry = null
                list = null
                continue
            }
            val current = section ?: continue
            when {
                line.startsWith("- topic:") -> {
                    entry = Entry(current, line.removePrefix("- topic:").trim()).also { entries += it }
                    list = null
                }

                entry == null -> Unit

                line.startsWith("event_type:") -> {
                    entry.type = line.removePrefix("event_type:").trim()
                    list = null
                }

                line.startsWith("content_topic:") -> {
                    val value = line.removePrefix("content_topic:").trim()
                    entry.content =
                        requireNotNull(value.toBooleanStrictOrNull()) {
                            "topic ${entry.topic} has content_topic '$value', expected true or false"
                        }
                    list = null
                }

                line.startsWith("record_topic:") -> {
                    val value = line.removePrefix("record_topic:").trim()
                    entry.record =
                        requireNotNull(value.toBooleanStrictOrNull()) {
                            "topic ${entry.topic} has record_topic '$value', expected true or false"
                        }
                    list = null
                }

                line == "event_types:" -> list = entry.types

                line == "producers:" -> list = entry.producers

                line == "subscribers:" -> list = entry.subscribers

                line.startsWith("- ") && list != null -> list += line.removePrefix("- ").trim()

                else -> list = null // another key (an empty list `[]`, ...)
            }
        }
        return EventCatalogue(
            entries.filter { it.section == Section.TOPICS }.map(::eventEntry),
            entries.filter { it.section == Section.REPLAY }.map(::replayTopic),
            entries.filter { it.section == Section.REQUEST }.map(::requestTopic),
        )
    }

    private fun eventEntry(entry: Entry): CatalogueEntry {
        val eventType = requireNotNull(entry.type) { "topic ${entry.topic} has no event_type" }
        require(entry.producers.isNotEmpty()) { "topic ${entry.topic} has no producer" }
        return CatalogueEntry(eventType, entry.topic, entry.producers.toSet(), entry.content, entry.record)
    }

    private fun replayTopic(entry: Entry): ReplayTopic {
        require(entry.producers.size == 1) { "replay topic ${entry.topic} needs exactly one producer" }
        require(entry.subscribers.size == 1) { "replay topic ${entry.topic} needs exactly one subscriber" }
        return ReplayTopic(
            entry.topic,
            entry.types.toSet(),
            entry.producers.single(),
            entry.subscribers.single(),
            requireNotNull(entry.content) { "replay topic ${entry.topic} has no content_topic" },
        )
    }

    private fun requestTopic(entry: Entry): ReplayRequestTopic {
        require(entry.producers.size == 1) { "request topic ${entry.topic} needs exactly one producer (its consumer)" }
        require(entry.types == setOf(ReplayProtocol.REPLAY_REQUESTED)) {
            "request topic ${entry.topic} carries only ReplayRequested"
        }
        return ReplayRequestTopic(entry.topic, entry.producers.single(), entry.subscribers.toSet())
    }
}
