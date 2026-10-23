package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** INV-REQ-1201 behaviour #1: lag measured from the monotonic version. */
class ReplicaLagTrackerTest {
    private val clock = MutableClock()
    private val tracker = ReplicaLagTracker(clock).also { it.onSnapshotComplete(0) }

    private fun lag(seconds: Long) = Lag.Measured(Duration.ofSeconds(seconds))

    @Test
    fun `events alone never make lag known, only a snapshot marker does`() {
        val restarted = ReplicaLagTracker(clock)
        restarted.onApplied(900) // a feed resumed mid-log
        restarted.observeHead(900)
        restarted.lag() shouldBe Lag.Unknown
        restarted.onSnapshotComplete(900)
        restarted.lag() shouldBe lag(0)
    }

    @Test
    fun `unknown before any contact`() {
        ReplicaLagTracker(clock).lag() shouldBe Lag.Unknown
        Lag.Unknown.isWithin(Duration.ofDays(999)) shouldBe false
    }

    @Test
    fun `caught up and connected stays at zero however long it is quiet`() {
        tracker.onApplied(3)
        clock.advance(Duration.ofHours(1))
        tracker.lag() shouldBe lag(0)
        tracker.observeHead(3)
        tracker.lag() shouldBe lag(0)
    }

    @Test
    fun `behind grows from when the newer head was first seen and closes when applied`() {
        tracker.onApplied(3)
        tracker.observeHead(5)
        clock.advance(Duration.ofMillis(400))
        tracker.observeHead(6) // a newer head does not restart the clock
        clock.advance(Duration.ofMillis(600))
        tracker.lag() shouldBe lag(1)
        tracker.lag().isWithin(Duration.ofSeconds(1)) shouldBe true
        tracker.onApplied(6)
        tracker.lag() shouldBe lag(0)
    }

    @Test
    fun `a dropped feed makes a caught up replica age from its last contact`() {
        tracker.onApplied(3)
        tracker.onDisconnected()
        clock.advance(Duration.ofSeconds(7))
        tracker.lag() shouldBe lag(7)
        tracker.observeHead(3) // reconnected
        tracker.lag() shouldBe lag(0)
    }

    private fun ms(n: Long) = Lag.Measured(Duration.ofMillis(n))

    private fun heartbeatTracker(): ReplicaLagTracker =
        ReplicaLagTracker(clock).also {
            it.onSnapshotComplete(5)
            it.expectHeartbeats(Duration.ofMillis(1000))
        }

    @Test
    fun `silence beyond the bound counts as a lost feed and a heartbeat ends it`() {
        val t = heartbeatTracker()
        clock.advance(Duration.ofMillis(1000))
        t.lag() shouldBe ms(0)
        clock.advance(Duration.ofMillis(1))
        t.lag() shouldBe ms(1001)
        clock.advance(Duration.ofMillis(499))
        t.observeHead(5)
        t.lag() shouldBe ms(0)
    }

    @Test
    fun `behind since the head was seen is unchanged by the silence bound`() {
        val t = heartbeatTracker()
        t.observeHead(9)
        clock.advance(Duration.ofMillis(300))
        t.lag() shouldBe ms(300)
    }

    @Test
    fun `silence before the snapshot marker stays unknown`() {
        val t = ReplicaLagTracker(clock).also { it.expectHeartbeats(Duration.ofMillis(1000)) }
        clock.advance(Duration.ofHours(1))
        t.lag() shouldBe Lag.Unknown
    }

    @Test
    fun `without expectHeartbeats silence changes nothing and disconnect still marks the feed lost`() {
        tracker.onApplied(1)
        clock.advance(Duration.ofMinutes(10))
        tracker.lag() shouldBe ms(0)
        tracker.onDisconnected()
        tracker.lag() shouldBe Lag.Measured(Duration.ofMinutes(10))
    }
}
