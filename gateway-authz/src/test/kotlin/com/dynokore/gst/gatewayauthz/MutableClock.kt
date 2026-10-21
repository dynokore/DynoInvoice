package com.dynokore.gst.gatewayauthz

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A test clock moved by hand. */
class MutableClock(
    @Volatile var now: Instant = Instant.parse("2026-10-12T08:00:00Z"),
) : Clock() {
    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}
