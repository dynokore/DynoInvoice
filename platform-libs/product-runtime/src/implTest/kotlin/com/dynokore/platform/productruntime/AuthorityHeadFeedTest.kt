package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.feed.AuthorityHeadFeed
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Lag
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** INV-T-9059: AuthorityHeadObserved feeds the replica head from the generated payload type (INV-Q-13597 A). */
class AuthorityHeadFeedTest {
    private val replica = AuthorityReplica(CLOCK)
    private val feed = AuthorityHeadFeed(replica)
    private val head = ProductEventTypes.AUTHORITY_HEAD_OBSERVED

    @Test
    fun `the type is the access heartbeat type`() {
        head shouldBe "com.dynokore.gst.access.AuthorityHeadObserved.v1"
    }

    @Test
    fun `a heartbeat moves the head the tracker measures its lag against`() {
        replica.tracker.onSnapshotComplete(3)
        feed.handle(envelope(head, """{"authorityVersion":9,"observedAt":"2026-10-12T02:00:00Z"}""")) shouldBe
            HandleOutcome.APPLIED
        replica.tracker.lag().shouldBeInstanceOf<Lag.Measured>()
        replica.tracker.appliedVersion() shouldBe 3
        feed.decode(envelope(head, """{"authorityVersion":9}""")) shouldBe 9L
    }

    @Test
    fun `a payload without authorityVersion or with a value below 1 is a ContractViolation`() {
        listOf(
            """{}""",
            """{"observedAt":"2026-10-12T02:00:00Z"}""",
            """{"authorityVersion":0}""",
            """{"authorityVersion":-4}""",
        ).forEach { json -> assertThrows<ContractViolation>(json) { feed.handle(envelope(head, json)) } }
        replica.tracker.lag() shouldBe Lag.Unknown
    }

    @Test
    fun `an event of another type is a ContractViolation, an AuthorityChanged included`() {
        val grants = """{"authorityVersion":5,"principalRef":"P-1","changeKind":"GRANT"}"""
        listOf(
            PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
            "com.dynokore.gst.access.AuthorityHeadObserved.v2",
            "com.dynokore.gst.platform.AuthorityHeadObserved.v1",
        ).forEach { type ->
            assertThrows<ContractViolation>(type) { feed.handle(envelope(type, grants)) }
                .message shouldContain "takes no"
        }
    }
}
