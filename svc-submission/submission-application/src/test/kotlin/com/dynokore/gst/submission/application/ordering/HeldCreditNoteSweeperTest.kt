package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.CodeSignals
import com.dynokore.gst.submission.application.arrival.RaisedCode
import com.dynokore.gst.submission.application.arrival.RaisedCodes
import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.queue.AcknowledgementBasis
import com.dynokore.gst.submission.application.queue.AcknowledgementInputs
import com.dynokore.gst.submission.application.queue.CatalogueEntry
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.application.queue.VersionState
import com.dynokore.gst.submission.domain.ordering.CreditNoteOption
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** The deadline and safety sweep of the held credit notes over fakes (INV-AC-24523). */
class HeldCreditNoteSweeperTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val p1 = "00000000-0000-0000-0000-0000000000a1"
    private val c1 = "00000000-0000-0000-0000-0000000000c1"
    private val c2 = "00000000-0000-0000-0000-0000000000c2"
    private val c5 = "00000000-0000-0000-0000-0000000000c5"
    private val zone = ZoneId.of("Asia/Singapore")
    private val releaseAlone = Instant.parse("2026-03-18T16:00:00Z")

    private var now = Instant.parse("2026-03-10T00:00:00Z")
    private var option = CreditNoteOption.RELEASE_ALONE
    private var preceding: List<LifecycleState>? = listOf(LifecycleState.QUEUED)
    private var dueDate: LocalDate? = LocalDate.parse("2026-03-20")
    private var failFor: String? = null
    private var bound: TenantId? = null
    private var optionDate: LocalDate? = null
    private val heldRows = mutableMapOf(t1 to mutableListOf(c1, c2), t2 to mutableListOf<String>())
    private val calls = mutableListOf<String>()
    private val raised = mutableListOf<RaisedCode>()
    private val stateReadsBy = mutableListOf<Pair<TenantId, TenantId?>>()
    private val heldLimits = mutableListOf<Int>()
    private val outsideBound = mutableListOf<String>()

    private fun held(doc: String) = HeldVersion(doc, 1, p1, "E-001", "2026-Q1")

    private fun checkBound(
        tenant: TenantId,
        what: String,
    ) {
        if (bound != tenant) outsideBound += "$what:$tenant:$bound"
    }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = checkNotNull(bound)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T {
                bound = tenant
                try {
                    return block()
                } finally {
                    bound = null
                }
            }
        }

    /** A transaction that restores the recorded writes when its block throws. */
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                val callsBefore = calls.size
                val raisedBefore = raised.size
                val heldBefore = heldRows.mapValues { it.value.toList() }
                try {
                    return block()
                } catch (e: RuntimeException) {
                    while (calls.size > callsBefore) calls.removeLast()
                    while (raised.size > raisedBefore) raised.removeLast()
                    heldBefore.forEach { (t, rows) -> heldRows.getValue(t).apply { clear() }.addAll(rows) }
                    throw e
                }
            }
        }

    private val scope = TenantTransactions(tenants, runner, WorkloadPrincipal("submission-svc"))

    private val inFlight =
        object : InFlightStore {
            override fun lockVersions(
                tenant: TenantId,
                docUuid: String,
            ): List<VersionState> = listOf(VersionState(1, LifecycleState.READY))

            override fun waitingDocUuids(
                tenant: TenantId,
                limit: Int,
            ): List<String> = error("not used")
        }

    private val submissions: SubmissionStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SubmissionStore::class.java)) { _, m, _ ->
            check(m.name.substringBefore('-') == "moveReadyToQueued") { "unexpected ${m.name}" }
            1
        } as SubmissionStore

    private val workQueue =
        object : WorkQueue {
            override fun enqueue(entry: QueueEntry): Long {
                if (entry.partitionKey == failFor) error("queue down")
                calls += "enqueue:${entry.partitionKey}:${entry.lane}"
                return 1
            }

            override fun claimNext(): ClaimedItem? = error("not used")

            override fun payloadOf(item: ClaimedItem): String = error("not used")

            override fun complete(item: ClaimedItem) = error("not used")

            override fun release(item: ClaimedItem) = error("not used")
        }

    private val queue = SubmissionQueue(tenants, runner, submissions, inFlight, workQueue)

    private val source = HeldTenantSource { listOf(t1, t2) }

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
                checkBound(tenant, "releaseHeld")
                calls += "release:$tenant:$docUuid:$version"
                return if (heldRows.getValue(tenant).remove(docUuid)) 1 else 0
            }

            override fun heldBy(
                tenant: TenantId,
                precedingDocUuid: String,
            ): List<HeldVersion> = error("not used")

            override fun held(
                tenant: TenantId,
                limit: Int,
            ): List<HeldVersion> {
                checkBound(tenant, "held")
                heldLimits += limit
                return heldRows.getValue(tenant).take(limit).map { held(it) }
            }
        }

    private val states =
        PrecedingStates { tenant, _ ->
            checkBound(tenant, "states")
            stateReadsBy += tenant to bound
            // a T1 read never answers for T2: T2 does not see the preceding document of T1
            if (tenant == t1) preceding else null
        }

    private val options =
        CreditNoteOptions { tenant, _, on ->
            checkBound(tenant, "options")
            optionDate = on
            option
        }

    private val releaseAt =
        ReleaseAloneAt(
            AcknowledgementInputs { _, _ ->
                AcknowledgementBasis(dueDate, SafetyMarginAnswer.Known(checkNotNull(PackMargin.of(2, "DAYS"))))
            },
            zone,
        )

    private val codes =
        object : RaisedCodes {
            override fun append(
                tenant: TenantId,
                raised: RaisedCode,
                raisedAt: Instant,
            ): Boolean {
                checkBound(tenant, "append")
                if (this@HeldCreditNoteSweeperTest.raised.any {
                        it.docUuid == raised.docUuid && it.code == raised.code
                    }
                ) {
                    return false
                }
                this@HeldCreditNoteSweeperTest.raised += raised
                calls +=
                    "raise:${raised.code}:${raised.docUuid}:${raised.version}:${raised.entityRef}:${raised.periodRef}"
                return true
            }
        }

    private val raising =
        CodeRaising(
            ErrorCatalogue { CatalogueEntry(it, "Tax operations", "MANUAL") },
            codes,
            object : CodeSignals {
                override fun stage(raised: RaisedCode) = Unit
            },
            Clock.fixed(Instant.parse("2026-03-10T00:00:00Z"), ZoneOffset.UTC),
        )

    private fun sweep(): Int {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        return HeldCreditNoteSweeper(
            source,
            scope,
            store,
            states,
            options,
            releaseAt,
            queue,
            raising,
            clock,
            zone,
        ).sweep()
    }

    private fun enqueued(
        doc: String,
        lane: String,
    ) = "enqueue:$doc:$lane"

    private fun release(
        doc: String,
        tenant: TenantId = t1,
    ) = "release:$tenant:$doc:1"

    private fun <T> logged(block: () -> T): Pair<T, List<LogRecord>> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        val logger = Logger.getLogger(HeldCreditNoteSweeper::class.java.name)
        logger.addHandler(handler)
        try {
            return block() to records
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun `INV-AC-24523 case 1 a pending original before the release-alone instant writes nothing`() {
        sweep() shouldBe 0
        calls shouldBe emptyList()
        releaseAt.of(t1, c1) shouldBe releaseAlone
    }

    @Test
    fun `INV-AC-24523 case 2 RELEASE_ALONE at the instant raises SUB-012 once then releases in DEADLINE_NEAR`() {
        now = Instant.parse("2026-03-19T00:00:00Z")
        sweep() shouldBe 2
        calls shouldBe
            listOf(
                "raise:SUB-012:$c1:1:E-001:2026-Q1",
                release(c1),
                enqueued(c1, "DEADLINE_NEAR"),
                "raise:SUB-012:$c2:1:E-001:2026-Q1",
                release(c2),
                enqueued(c2, "DEADLINE_NEAR"),
            )
        optionDate shouldBe LocalDate.parse("2026-03-19")
        calls.clear()
        sweep() shouldBe 0
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24523 case 3 HOLD_FOR_APPROVER at the instant raises SUB-012 once and keeps C1 held`() {
        now = Instant.parse("2026-03-19T00:00:00Z")
        option = CreditNoteOption.HOLD_FOR_APPROVER
        sweep() shouldBe 0
        calls shouldBe
            listOf("raise:SUB-012:$c1:1:E-001:2026-Q1", "raise:SUB-012:$c2:1:E-001:2026-Q1")
        sweep() shouldBe 0
        raised.map { it.docUuid to it.code } shouldBe listOf(c1 to "SUB-012", c2 to "SUB-012")
        heldRows.getValue(t1) shouldBe mutableListOf(c1, c2)
    }

    @Test
    fun `INV-AC-24523 case 4 an ACKNOWLEDGED original releases through LIVE without SUB-012`() {
        preceding = listOf(LifecycleState.ACKNOWLEDGED)
        sweep() shouldBe 2
        calls shouldBe listOf(release(c1), enqueued(c1, "LIVE"), release(c2), enqueued(c2, "LIVE"))
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24523 case 5 a CANCELLED original releases through LIVE without SUB-012`() {
        preceding = listOf(LifecycleState.CANCELLED)
        now = Instant.parse("2026-03-19T00:00:00Z")
        sweep() shouldBe 2
        calls shouldBe listOf(release(c1), enqueued(c1, "LIVE"), release(c2), enqueued(c2, "LIVE"))
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24523 case 6 an unknown deadline holds a pending original with one WARNING per version`() {
        dueDate = null
        now = Instant.parse("2026-03-19T00:00:00Z")
        val (released, records) = logged { sweep() }
        released shouldBe 0
        calls shouldBe emptyList()
        records.count { it.level == Level.WARNING } shouldBe 2
    }

    @Test
    fun `INV-AC-24523 case 6 an unknown deadline still releases an ACKNOWLEDGED original`() {
        dueDate = null
        preceding = listOf(LifecycleState.ACKNOWLEDGED)
        sweep() shouldBe 2
        calls shouldBe listOf(release(c1), enqueued(c1, "LIVE"), release(c2), enqueued(c2, "LIVE"))
    }

    @Test
    fun `INV-AC-24523 case 7 a queue failure of C1 is logged at ERROR and rolled back while C2 is released`() {
        preceding = listOf(LifecycleState.ACKNOWLEDGED)
        failFor = c1
        val (released, records) = logged { sweep() }
        released shouldBe 1
        records.count { it.level == Level.SEVERE } shouldBe 1
        calls shouldBe listOf(release(c2), enqueued(c2, "LIVE"))
        heldRows.getValue(t1) shouldBe mutableListOf(c1)
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24523 case 7 a queue failure rolls back the SUB-012 row of the same version`() {
        now = Instant.parse("2026-03-19T00:00:00Z")
        failFor = c1
        sweep() shouldBe 1
        raised.map { it.docUuid } shouldBe listOf(c2)
        heldRows.getValue(t1) shouldBe mutableListOf(c1)
    }

    @Test
    fun `INV-AC-24523 case 8 every access is inside the bound tenant, T2 reads no T1 state and the batch is 100`() {
        preceding = listOf(LifecycleState.ACKNOWLEDGED)
        heldRows.getValue(t2) += c5
        sweep() shouldBe 2
        outsideBound shouldBe emptyList()
        stateReadsBy.filter { it.first == t2 } shouldBe listOf(t2 to t2)
        heldRows.getValue(t2) shouldBe mutableListOf(c5)
        heldLimits shouldBe listOf(100, 100)
        HeldCreditNoteSweeper.BATCH shouldBe 100
        HeldCreditNoteSweeper.INTERVAL.seconds shouldBe 60
    }
}
