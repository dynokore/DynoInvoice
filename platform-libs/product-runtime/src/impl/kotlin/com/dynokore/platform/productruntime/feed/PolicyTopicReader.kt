package com.dynokore.platform.productruntime.feed

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EventLogHead
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.RebuildRequired
import com.dynokore.platform.serviceruntime.replica.PolicyReplica

/**
 * The one shared reader of the platform policy topic (INV-REQ-1241, INV-AC-24766; owner ruling 2026-11-18 A): reads
 * [topic] through [EventLogReader], hands each record to [feed], reports the head and the snapshot to [replica] and
 * resumes from the position the replica holds, so the position can never run ahead of the state it describes.
 *
 * A record that breaks the contract is skipped with an incident naming the topic and offset and is not read again.
 * `RebuildRequired` stops the topic in this process (fail closed: values stay absent); any other failure disconnects
 * the topic and the next poll retries. [incident] receives each incident line; it defaults to a WARNING log.
 */
@Suppress("LongParameterList") // constructor injection under CS-05
class PolicyTopicReader(
    private val reader: EventLogReader,
    private val head: EventLogHead?,
    private val feed: PolicyFeed,
    private val replica: PolicyReplica,
    private val topic: String = TOPIC,
    private val batch: Int = 100,
    private val incident: (String) -> Unit = { LOG.log(System.Logger.Level.WARNING, it) },
) {
    private var seen = -1L
    private var snapshotReported = false
    private var stopped = false

    /** True once `RebuildRequired` has stopped this topic. */
    @get:Synchronized
    val isStopped: Boolean get() = stopped

    /** One read of at most [batch] records after the current position; never throws. */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // any failure disconnects the topic and the next poll retries
    fun pollOnce() {
        if (stopped) return
        val after = maxOf(replica.appliedOffset(topic), seen, 0L)
        try {
            reader.read(topic, after, batch).forEach { record ->
                try {
                    feed.accept(topic, record)
                } catch (e: ContractViolation) {
                    incident("policy topic $topic offset ${record.offset} skipped: ${e.message}")
                }
                seen = maxOf(seen, record.offset)
            }
            reportHead(maxOf(after, seen))
        } catch (e: RebuildRequired) {
            stopped = true
            replica.onDisconnected(topic)
            incident(
                "policy topic $topic stopped: rebuild required, offset ${e.nextOffset} <= watermark ${e.watermark}",
            )
        } catch (e: Exception) {
            replica.onDisconnected(topic)
            incident("policy topic $topic read failed, will retry: ${e.message}")
        }
    }

    private fun reportHead(position: Long) {
        val h = head?.head(topic, position)?.head ?: return
        replica.observeHead(topic, h)
        if (!snapshotReported && position >= h) {
            replica.onSnapshotComplete(topic, position)
            snapshotReported = true
        }
    }

    companion object {
        const val TOPIC = "gst.platform.policy-changed.v1"
        private val LOG: System.Logger = System.getLogger(PolicyTopicReader::class.java.name)
    }
}
