package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.ReplayMode
import com.dynokore.platform.serviceruntime.rebuild.ConsumerRebuildRuntime
import com.dynokore.platform.serviceruntime.rebuild.RebuildOutcome
import com.dynokore.platform.serviceruntime.rebuild.ReplayState
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Lag
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * INV-T-9262, INV-AC-9051: a real [AuthorityReplica] wired to [ReplicaColdStartSync]; the first change of a higher
 * incarnation clears the replica, leaves the lag Unknown and asks access-svc for the snapshot at once.
 */
class ReplicaColdStartEpochTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val replica = AuthorityReplica(Clock.systemUTC(), OperationRoleMap { emptyMap() })
    private val runtime = mockk<ConsumerRebuildRuntime>()
    private val replayId = UUID.randomUUID()
    private val state =
        ReplayState(
            replayId,
            ReplicaColdStartSync.TOPIC,
            ReplicaColdStartSync.ACCESS,
            listOf(ReplicaColdStartSync.ACCESS),
            ReplayMode.FULL_SYNC,
            null,
            Instant.EPOCH,
            false,
        )

    private fun change(incarnation: Long) = replica.accept(AuthorityChanged(tenant, "p1", 1, null, incarnation))

    private fun synced() {
        replica.tracker.onSnapshotComplete(1)
        replica.observeHead(1, 1)
    }

    @Test
    fun `INV-AC-9051 higherIncarnationRequestsSnapshot`() {
        every { runtime.requestColdStartFullSync(any(), any(), any(), any()) } returns
            RebuildOutcome.Done(state, true)
        val sync = ReplicaColdStartSync(runtime, replica)
        change(1)
        synced()
        replica.lag() shouldBe Lag.Measured(java.time.Duration.ZERO)
        change(2)
        replica.lag() shouldBe Lag.Unknown
        verify(timeout = 5000, exactly = 1) {
            runtime.requestColdStartFullSync(
                ReplicaColdStartSync.TOPIC,
                any<CorrelationId>(),
                any<CausationId>(),
                "access-svc",
            )
        }
        sync.isOwnReplay(replayId) shouldBe true
    }

    @Test
    fun `INV-AC-9051 higherIncarnationWithOwnReplayPendingAnswersRepeat`() {
        // the runtime answers like the real one: created for the first request, a repeat while it is pending
        every { runtime.requestColdStartFullSync(any(), any(), any(), any()) } returnsMany
            listOf(RebuildOutcome.Done(state, true), RebuildOutcome.Done(state.copy(repeat = true), false))
        val sync = ReplicaColdStartSync(runtime, replica)
        change(1)
        val first = sync.sync() as RebuildOutcome.Done
        first.created shouldBe true
        change(2)
        verify(timeout = 5000, exactly = 2) { runtime.requestColdStartFullSync(any(), any(), any(), any()) }
        val repeat = sync.sync()
        // the third request is the real runtime's repeat answer: no new row, the replay id stays own
        verify(timeout = 5000, exactly = 3) { runtime.requestColdStartFullSync(any(), any(), any(), any()) }
        (repeat as RebuildOutcome.Done).created shouldBe false
        repeat.state.replayId shouldBe replayId
        sync.isOwnReplay(replayId) shouldBe true
    }
}
