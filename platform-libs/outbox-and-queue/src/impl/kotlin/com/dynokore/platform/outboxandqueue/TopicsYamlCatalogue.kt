package com.dynokore.platform.outboxandqueue

/**
 * Reads the event catalogue from platform-contracts' generated `event-log/topics.yaml`. Three sections:
 * - `topics`: one entry per event topic: `topic`, `event_type`, `producers`, `subscribers`, from INV-T-3386
 *   `content_topic: true|false` (INV-REQ-1205 b4; an entry without it gets [CatalogueEntry.contentTopic] `null`) and
 *   from INV-T-3606 `record_topic` ([CatalogueEntry.recordTopic], INV-Q-3809) and `retained_while_resolvable:
 * true|false`
 *   (INV-REQ-1210, INV-Q-6281; an entry without it gets [CatalogueEntry.retainedWhileResolvable] `null`, a replay topic
 *   without it is refused);
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

    private enum class Key { TYPES, PRODUCERS, SUBSCRIBERS }

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
        var retained: Boolean? = null

        fun values(key: Key): MutableSet<String> =
            when (key) {
                Key.TYPES -> types
                Key.PRODUCERS -> producers
                Key.SUBSCRIBERS -> subscribers
            }
    }

    /** The line reader: the section, entry and list a line belongs to are the state between lines. */
    private class LineReader {
        val entries = mutableListOf<Entry>()
        private var section: Section? = null
        private var entry: Entry? = null
        private var list: Key? = null

        fun feed(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return
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
            } else {
                section?.let { feedEntryLine(it, line) }
            }
        }

        private fun feedEntryLine(
            current: Section,
            line: String,
        ) {
            val open = entry
            when {
                line.startsWith("- topic:") -> {
                    entry = Entry(current, line.removePrefix("- topic:").trim()).also { entries += it }
                    list = null
                }

                open != null -> {
                    feedKey(open, line)
                }
            }
        }

        private fun feedKey(
            open: Entry,
            line: String,
        ) {
            when {
                line.startsWith("event_type:") -> {
                    open.type = line.removePrefix("event_type:").trim()
                    list = null
                }

                line.startsWith("content_topic:") -> {
                    open.content = flag(open, "content_topic", line)
                    list = null
                }

                line.startsWith("retained_while_resolvable:") -> {
                    open.retained = flag(open, "retained_while_resolvable", line)
                    list = null
                }

                line.startsWith("record_topic:") -> {
                    open.record = flag(open, "record_topic", line)
                    list = null
                }

                line == "event_types:" -> {
                    list = Key.TYPES
                }

                line == "producers:" -> {
                    list = Key.PRODUCERS
                }

                line == "subscribers:" -> {
                    list = Key.SUBSCRIBERS
                }

                line.startsWith("- ") && list != null -> {
                    list?.let { open.values(it) }?.add(line.removePrefix("- ").trim())
                }

                else -> {
                    list = null
                } // another key (an empty list `[]`, ...)
            }
        }

        private fun flag(
            open: Entry,
            key: String,
            line: String,
        ): Boolean {
            val value = line.removePrefix("$key:").trim()
            return requireNotNull(value.toBooleanStrictOrNull()) {
                "topic ${open.topic} has $key '$value', expected true or false"
            }
        }
    }

    /** Parses [text] into an [EventCatalogue]; throws [IllegalArgumentException] on a malformed entry. */
    fun parse(text: String): EventCatalogue {
        val reader = LineReader()
        text.lines().forEach(reader::feed)
        val entries = reader.entries
        return EventCatalogue(
            entries.filter { it.section == Section.TOPICS }.map(::eventEntry),
            entries.filter { it.section == Section.REPLAY }.map(::replayTopic),
            entries.filter { it.section == Section.REQUEST }.map(::requestTopic),
        )
    }

    private fun eventEntry(entry: Entry): CatalogueEntry {
        val eventType = requireNotNull(entry.type) { "topic ${entry.topic} has no event_type" }
        require(entry.producers.isNotEmpty()) { "topic ${entry.topic} has no producer" }
        return CatalogueEntry(
            eventType,
            entry.topic,
            entry.producers.toSet(),
            entry.content,
            entry.record,
            entry.retained,
        )
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
            requireNotNull(entry.retained) { "replay topic ${entry.topic} has no retained_while_resolvable" },
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
