package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.queue.VersionNotReady
import com.dynokore.gst.submission.application.queue.VersionState
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

/** The release of held credit notes at the acknowledgement of the preceding document (INV-AC-24522). */
class HeldReleaseTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val p1 = "00000000-0000-0000-0000-0000000000a1"
    private val c1 = "00000000-0000-0000-0000-0000000000c1"
    private val c2 = "00000000-0000-0000-0000-0000000000c2"
    private val calls = mutableListOf<String>()
    private var held = listOf(held(c1), held(c2))
    private val releaseCounts = mutableMapOf<String, Int>()
    private var preceding: List<LifecycleState>? = listOf(LifecycleState.ACKNOWLEDGED)
    private var statesReads = 0
    private val blockedBy = mutableMapOf<String, Int>()
    private val notReady = mutableSetOf<String>()

    private fun held(doc: String) = HeldVersion(doc, 1, p1, "E-001", "2026-Q1")

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = t1

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private val inFlight =
        object : InFlightStore {
            override fun lockVersions(
                tenant: TenantId,
                docUuid: String,
            ): List<VersionState> {
                if (docUuid in notReady) return emptyList()
                val other = blockedBy[docUuid]?.let { listOf(VersionState(it, LifecycleState.QUEUED)) }.orEmpty()
                return other + VersionState(1, LifecycleState.READY)
            }

            override fun waitingDocUuids(
                tenant: TenantId,
                limit: Int,
            ): List<String> = error("not used")
        }

    private val submissions: SubmissionStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SubmissionStore::class.java)) { _, m, a ->
            check(m.name.substringBefore('-') == "moveReadyToQueued") { "unexpected ${m.name}" }
            calls += "move:${a[1]}"
            1
        } as SubmissionStore

    private val workQueue =
        object : WorkQueue {
            override fun enqueue(entry: QueueEntry): Long {
                calls += "enqueue:${entry.partitionKey}:${entry.lane}"
                return 1
            }

            override fun claimNext(): ClaimedItem? = error("not used")

            override fun payloadOf(item: ClaimedItem): String = error("not used")

            override fun complete(item: ClaimedItem) = error("not used")

            override fun release(item: ClaimedItem) = error("not used")
        }

    private val queue = SubmissionQueue(tenants, runner, submissions, inFlight, workQueue)

    private val store =
        object : HoldStore {
            override fun holdReady(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): Int = error("not used")

            override fun releaseHeld(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): Int {
                calls += "release:$docUuid:$version"
                return releaseCounts[docUuid] ?: 1
            }

            override fun heldBy(
                tenant: TenantId,
                precedingDocUuid: String,
            ): List<HeldVersion> = held

            override fun held(
                tenant: TenantId,
                limit: Int,
            ): List<HeldVersion> = error("not used")
        }

    private val states =
        PrecedingStates { _, _ ->
            statesReads++
            preceding
        }

    private val release = HeldRelease(store, states, queue)

    private fun enqueued(doc: String) = "enqueue:$doc:${Lane.LIVE}"

    @Test
    fun `INV-AC-24522 an ACKNOWLEDGED original releases both versions in order and enqueues each in lane LIVE`() {
        release.acknowledged(t1, p1)
        calls.filter { it.startsWith("release:") || it.startsWith("enqueue:") } shouldBe
            listOf("release:$c1:1", enqueued(c1), "release:$c2:1", enqueued(c2))
    }

    @Test
    fun `INV-AC-24522 a QUEUED original releases nothing`() {
        preceding = listOf(LifecycleState.QUEUED)
        release.acknowledged(t1, p1)
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24522 a CANCELLED original releases nothing`() {
        preceding = listOf(LifecycleState.CANCELLED)
        release.acknowledged(t1, p1)
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24522 an unknown original releases nothing`() {
        preceding = null
        release.acknowledged(t1, p1)
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24522 a release that moved no row is not enqueued and the next version is released`() {
        releaseCounts[c1] = 0
        release.acknowledged(t1, p1)
        calls.filter { it.startsWith("release:") || it.startsWith("enqueue:") } shouldBe
            listOf("release:$c1:1", "release:$c2:1", enqueued(c2))
    }

    @Test
    fun `INV-AC-24522 a Waiting answer is not an error and the next version is still released`() {
        blockedBy[c1] = 2
        release.acknowledged(t1, p1)
        calls.contains(enqueued(c1)) shouldBe false
        calls.contains(enqueued(c2)) shouldBe true
        calls.count { it.startsWith("release:") } shouldBe 2
    }

    @Test
    fun `INV-AC-24522 a VersionNotReady from the queue propagates`() {
        notReady += c1
        shouldThrow<VersionNotReady> { release.acknowledged(t1, p1) }
        calls.contains(enqueued(c2)) shouldBe false
    }

    @Test
    fun `INV-AC-24522 no held version reads no state and releases nothing`() {
        held = emptyList()
        release.acknowledged(t1, p1)
        statesReads shouldBe 0
        calls shouldBe emptyList()
    }
}
