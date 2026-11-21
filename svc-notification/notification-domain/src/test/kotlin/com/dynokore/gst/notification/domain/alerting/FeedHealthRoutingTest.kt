package com.dynokore.gst.notification.domain.alerting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FeedHealthRoutingTest {
    @Test
    fun `INV-AC-26515 every control maps to its kind`() {
        feedAlertKind("SEQUENCE") shouldBe DefaultAlertKind.FEED_STALL
        feedAlertKind("COUNT") shouldBe DefaultAlertKind.FEED_STALL
        feedAlertKind("VALUE") shouldBe DefaultAlertKind.FEED_STALL
        feedAlertKind("WATERMARK") shouldBe DefaultAlertKind.WATERMARK_GAP
        feedAlertKind("MANIFEST") shouldBe DefaultAlertKind.MANIFEST_FAILURE
    }

    @Test
    fun `INV-AC-26515 an unknown control, a lower-case control and an empty control are refused`() {
        listOf("OTHER", "watermark", "WATERMARKS", "Sequence", "", " ").forEach {
            shouldThrow<IllegalArgumentException> { feedAlertKind(it) }
        }
    }

    @Test
    fun `INV-AC-26515 the feed alert class equals COVERAGE and is a hard-stop class`() {
        FEED_ALERT_CLASS shouldBe AlertClass.COVERAGE
        (FEED_ALERT_CLASS in HARD_STOP_CLASSES) shouldBe true
    }
}
