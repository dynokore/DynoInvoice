package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.productruntime.feed.AuthorityHeadFeed
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.ReplicaOrderCounter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** INV-T-18607, INV-AC-9051: the feeds pass the incarnation to a real replica, which orders by (incarnation, version). */
class AuthorityIncarnationFeedTest {
    private val counts = mutableMapOf<String, Int>()
    private val replica =
        AuthorityReplica(
            CLOCK,
            OperationRoleMap { emptyMap() },
            orderCounter = ReplicaOrderCounter { counts.merge(it, 1, Int::plus) },
        )
    private val changes = AuthorityFeedHandler(replica)
    private val heads = AuthorityHeadFeed(replica)
    private val changed = PlatformEventCatalogue.AUTHORITY_CHANGED.eventType
    private val head = PlatformEventCatalogue.AUTHORITY_HEAD_OBSERVED.eventType
    private val lower = ReplicaOrderCounter.LOWER_INCARNATION

    private fun change(
        version: Int,
        incarnation: Int?,
    ) = """{"authorityVersion":$version,"principalRef":"P-1","changeKind":"GRANT"${inc(incarnation)}}"""

    private fun inc(incarnation: Int?) = incarnation?.let { ""","incarnation":$it""" } ?: ""

    private fun apply(
        version: Int,
        incarnation: Int?,
    ) = changes.handle(envelope(changed, change(version, incarnation)))

    @Test
    fun `changeWithIncarnation2ClearsAfterIncarnation1`() {
        apply(90, 1)
        apply(1, 2)
        replica.tracker.incarnation() shouldBe 2L
        counts[lower] shouldBe null
        changes.decode(envelope(changed, change(1, 2))).incarnation shouldBe 2L
    }

    @Test
    fun `changeWithIncarnation1AfterIncarnation2IsRefusedAndCounted`() {
        apply(1, 2)
        apply(95, 1)
        replica.tracker.incarnation() shouldBe 2L
        counts[lower] shouldBe 1
    }

    @Test
    fun `headWithIncarnation1AfterIncarnation2IsRefusedAndCounted`() {
        heads.handle(envelope(head, """{"authorityVersion":1${inc(2)}}"""))
        heads.handle(envelope(head, """{"authorityVersion":500${inc(1)}}"""))
        replica.tracker.incarnation() shouldBe 2L
        counts[lower] shouldBe 1
        heads.decodeHead(envelope(head, """{"authorityVersion":4${inc(2)}}""")).incarnation shouldBe 2L
    }

    @Test
    fun `absentIncarnationIsEpoch1AndClearsNothing`() {
        apply(5, 1)
        apply(6, null)
        heads.handle(envelope(head, """{"authorityVersion":7}"""))
        changes.decode(envelope(changed, change(6, null))).incarnation shouldBe null
        heads.decodeHead(envelope(head, """{"authorityVersion":7}""")).incarnation shouldBe null
        replica.tracker.incarnation() shouldBe 1L
        counts[lower] shouldBe null
    }

    @Test
    fun `incarnationBelow1IsContractViolationForChangeAndHead`() {
        val before = replica.tracker.incarnation()
        listOf(0, -1).forEach { bad ->
            val c = envelope(changed, change(3, bad))
            assertThrows<ContractViolation> { changes.handle(c) }.message.shouldContain(c.id.toString())
            val h = envelope(head, """{"authorityVersion":3${inc(bad)}}""")
            assertThrows<ContractViolation> { heads.handle(h) }.message.shouldContain(h.id.toString())
        }
        replica.tracker.incarnation() shouldBe before
    }
}
