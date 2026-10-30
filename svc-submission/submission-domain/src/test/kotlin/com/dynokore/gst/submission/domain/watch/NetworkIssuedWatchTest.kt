package com.dynokore.gst.submission.domain.watch

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID

class NetworkIssuedWatchTest {
    private val doc = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val issued = Instant.parse("2026-04-18T09:00:00Z")
    private val four = Duration.ofHours(4)
    private val ref = VersionRef(doc, 1)

    private fun item(
        state: LifecycleState = LifecycleState.NETWORK_ISSUED,
        raised: Boolean = false,
        docUuid: UUID = doc,
        version: Int = 1,
        issuedAt: Instant = issued,
    ) = WatchItem(docUuid, version, state, issuedAt, raised)

    private fun eval(
        items: List<WatchItem>,
        threshold: Duration?,
        now: String,
    ) = NetworkIssuedWatch.evaluate(items, threshold, Instant.parse(now))

    @Test
    fun `INV-AC-350 one second past the threshold lists the version in raise`() {
        eval(listOf(item()), four, "2026-04-18T13:00:01Z") shouldBe WatchResult(listOf(ref), emptyList())
    }

    @Test
    fun `INV-AC-350 exactly at the threshold and before it list nothing`() {
        eval(listOf(item()), four, "2026-04-18T13:00:00Z") shouldBe WatchResult(emptyList(), emptyList())
        eval(listOf(item()), four, "2026-04-18T12:59:59Z") shouldBe WatchResult(emptyList(), emptyList())
    }

    @Test
    fun `INV-AC-350 every other state lists nothing`() {
        listOf(
            LifecycleState.ACKNOWLEDGED,
            LifecycleState.SENT,
            LifecycleState.SENT_UNCONFIRMED,
            LifecycleState.SUPERSEDED,
            LifecycleState.REJECTED,
        ).forEach { state ->
            eval(listOf(item(state = state)), four, "2026-04-18T14:00:00Z") shouldBe
                WatchResult(emptyList(), emptyList())
            eval(listOf(item(state = state)), null, "2026-04-18T14:00:00Z") shouldBe
                WatchResult(emptyList(), emptyList())
        }
    }

    @Test
    fun `INV-AC-350 a raised version lists nothing`() {
        eval(listOf(item(raised = true)), four, "2026-04-18T14:00:00Z") shouldBe WatchResult(emptyList(), emptyList())
        eval(listOf(item(raised = true)), null, "2026-04-18T14:00:00Z") shouldBe WatchResult(emptyList(), emptyList())
    }

    @Test
    fun `INV-AC-350 a threshold of 3 hours 59 minutes lists the version at 13 00`() {
        eval(listOf(item()), Duration.ofMinutes(239), "2026-04-18T13:00:00Z") shouldBe
            WatchResult(listOf(ref), emptyList())
    }

    @Test
    fun `INV-AC-350 two items keep the order and raise only the one past the threshold`() {
        val second = item(docUuid = UUID.fromString("00000000-0000-0000-0000-000000000002"), version = 2)
        val late = item(issuedAt = issued.plus(Duration.ofHours(2)))
        val items = listOf(item(docUuid = second.docUuid, version = 2), late, item(version = 3))
        eval(items, four, "2026-04-18T13:00:01Z").raise shouldBe
            listOf(VersionRef(second.docUuid, 2), VersionRef(doc, 3))
    }

    @Test
    fun `INV-AC-350 a null threshold lists a NETWORK_ISSUED version in thresholdMissing only`() {
        eval(listOf(item()), null, "2026-04-19T09:00:00Z") shouldBe WatchResult(emptyList(), listOf(ref))
    }

    @Test
    fun `INV-AC-350 a zero or negative threshold is refused also with an empty list`() {
        listOf(Duration.ZERO, Duration.ofMinutes(-1)).forEach { threshold ->
            assertThrows<IllegalArgumentException> { eval(listOf(item()), threshold, "2026-04-18T14:00:00Z") }
            assertThrows<IllegalArgumentException> { eval(emptyList(), threshold, "2026-04-18T14:00:00Z") }
        }
    }
}
