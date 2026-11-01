package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AcknowledgementReceived
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ApNotification
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PollAccessPointTest {
    private val adapter = AdapterId("peppol-ap-1")
    private val ledger = CountingLedger()
    private val events = CountingEvents()
    private val cursors = Cursors()
    private val receive = ReceiveApNotification(FakeOwners(), ledger, events, DirectUnitOfWork())
    private val a1 = ApNotification("APN-1", "ACKNOWLEDGEMENT", KNOWN_DOC, 1, "ACK-1")
    private val a2 = ApNotification("APN-2", "ACKNOWLEDGEMENT", KNOWN_DOC, 1, "ACK-2")

    private class Source(
        val page: NotificationPage,
    ) : NotificationSource {
        val askedAfter = mutableListOf<String?>()

        override fun pageAfter(
            adapter: AdapterId,
            cursor: String?,
        ): NotificationPage {
            askedAfter += cursor
            return page
        }
    }

    @Test
    fun `a page of two items gives two ledger records and the cursor stored once after both`() {
        cursors.values[adapter] = "c0"
        val source = Source(NotificationPage(listOf(a1, a2), "c2"))
        val report = PollAccessPoint(source, cursors, receive).run(adapter)
        source.askedAfter shouldBe listOf("c0")
        ledger.rows.size shouldBe 2
        events.published.size shouldBe 2
        cursors.writes shouldBe 1
        cursors.values[adapter] shouldBe "c2"
        report.processed shouldBe 2
    }

    @Test
    fun `a failure after translation and before the cursor store reads the same page and adds no second publish`() {
        val source = Source(NotificationPage(listOf(a1, a2), "c2"))
        val failing =
            object : Cursors() {
                var armed = true

                override fun write(
                    adapter: AdapterId,
                    cursor: String?,
                ) {
                    if (armed) {
                        armed = false
                        error("crash before the cursor store")
                    }
                    super.write(adapter, cursor)
                }
            }
        val poll = PollAccessPoint(source, failing, receive)
        shouldThrow<IllegalStateException> { poll.run(adapter) }
        events.published.size shouldBe 2
        poll.run(adapter).replays shouldBe 2
        source.askedAfter shouldBe listOf(null, null)
        events.published.size shouldBe 2
        failing.values[adapter] shouldBe "c2"
    }

    @Test
    fun `a refused item is reported and does not stop the page`() {
        val bad = ApNotification("APN-9", "IRAS_REJECTION", KNOWN_DOC, 1)
        val report = PollAccessPoint(Source(NotificationPage(listOf(bad, a1), "c1")), cursors, receive).run(adapter)
        report.refused shouldBe listOf("APN-9")
        events.published.map(AcknowledgementReceived::apNotificationId) shouldBe listOf("APN-1")
        cursors.values[adapter] shouldBe "c1"
    }

    @Test
    fun `a source that throws leaves the cursor unchanged`() {
        cursors.values[adapter] = "c0"
        val source =
            object : NotificationSource {
                override fun pageAfter(
                    adapter: AdapterId,
                    cursor: String?,
                ): NotificationPage = error("AP down")
            }
        shouldThrow<IllegalStateException> { PollAccessPoint(source, cursors, receive).run(adapter) }
        cursors.values[adapter] shouldBe "c0"
        cursors.writes shouldBe 0
    }
}
