package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.retry.DeadLetterReason
import com.dynokore.gst.submission.domain.retry.FailureResult
import com.dynokore.gst.submission.domain.transitions.RejectionReason
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The failure class written with a dead letter (INV-REQ-575, INV-AC-20586), against a recording store. */
class AttemptOutcomeFailureClassTest {
    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.randomUUID().toString()
    private val writes = mutableListOf<String>()
    private var bound: TenantId? = null

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = checkNotNull(bound)

            override fun currentActor(): String = "svc:submission-svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T {
                bound = tenant
                return block()
            }
        }
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
    private val scope = TenantTransactions(tenants, runner, WorkloadPrincipal("submission-svc"))

    private fun store(row: LockedSubmission): SubmissionStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SubmissionStore::class.java)) { _, m, a ->
            // a method taking the TenantId value class carries a mangled name suffix after '-'
            val name = m.name.substringBefore('-')
            when (name) {
                "lockForUpdate" -> {
                    row
                }

                "recordFailureClass" -> {
                    writes += "class ${a[3]} ${a[4]}"
                    null
                }

                else -> {
                    writes += name
                    null
                }
            }
        } as SubmissionStore

    private val versions =
        object : InFlightStore {
            override fun lockVersions(
                tenant: TenantId,
                docUuid: String,
            ): List<VersionState> = emptyList()

            override fun waitingDocUuids(
                tenant: TenantId,
                limit: Int,
            ): List<String> = emptyList()
        }
    private val queue =
        object : WorkQueue {
            override fun enqueue(entry: QueueEntry): Long = error("not used")

            override fun claimNext(): ClaimedItem? = null

            override fun payloadOf(item: ClaimedItem): String = error("not used")

            override fun complete(item: ClaimedItem) {
                writes += "complete"
            }

            override fun release(item: ClaimedItem) = Unit
        }
    private val noReporting =
        object : FailureReporting {
            override fun attemptFailed(
                dispatch: Dispatch,
                code: String,
            ) = Unit

            override fun versionFailed(
                dispatch: Dispatch,
                state: LifecycleState,
                rejectedCode: String?,
            ) = Unit
        }

    private fun row(
        failures: Int,
        first: Instant?,
    ) = LockedSubmission(
        LifecycleState.SENT,
        null,
        null,
        1,
        EventFacts("E-001", null),
        OutcomeFacts(null, failures, first),
    )

    private fun dispatch(attempt: Int) =
        Dispatch(doc, 1, "E-001", attempt, UUID.randomUUID(), null, ClaimedItem(1L, tenant, doc, Lane.LIVE))

    private fun applier(row: LockedSubmission) =
        AttemptOutcomeApplier(
            Clock.fixed(now, ZoneOffset.UTC),
            { _, _ -> Duration.ofHours(4) },
            { 0.0 },
            scope,
            store(row),
            queue,
            SubmissionQueue(tenants, runner, store(row), versions, queue),
            noReporting,
        )

    private val timeout = TransportResult.TransientFailure("SUB-001", "timeout".toByteArray())

    @Test
    fun `INV-AC-20586 V1 a retry-window exhaustion records SUB-001 on the attempt row of that dispatch`() {
        applier(row(2, now.minus(Duration.ofHours(5)))).apply(dispatch(3), timeout)

        writes shouldBe listOf("recordResponse", "setState", "class 3 SUB-001", "complete")
    }

    @Test
    fun `INV-AC-20586 V2 a retry records nothing`() {
        applier(row(0, null)).apply(dispatch(1), timeout)

        writes shouldBe listOf("recordResponse", "scheduleRetry", "complete")
    }

    @Test
    fun `INV-AC-20586 V3 an acceptance records nothing`() {
        applier(row(0, null)).apply(dispatch(1), TransportResult.Accepted("AP-1", now, "ok".toByteArray()))

        writes shouldBe listOf("recordResponse", "recordAccepted", "complete")
    }

    @Test
    fun `INV-AC-20586 V4 a rejection records nothing`() {
        val reasons = listOf(RejectionReason("R1", null, "a"))
        applier(row(0, null)).apply(dispatch(1), TransportResult.Rejected(reasons, "x".toByteArray()))

        writes shouldBe listOf("recordResponse", "setState", "complete")
    }

    @Test
    fun `INV-AC-20586 V5 a dead letter with no signal throws and nothing is written`() {
        val planned =
            FailureResult.DeadLetter(
                doc,
                1,
                LifecycleState.RETRY,
                DeadLetterReason.RETRY_WINDOW_EXHAUSTED,
                emptyList(),
            )

        tenants.bind(tenant, "svc:submission-svc") {
            shouldThrow<IllegalStateException> { applier(row(1, now)).deadLettered(dispatch(2), planned, "SUB-001") }
        }

        writes shouldBe emptyList()
    }
}
