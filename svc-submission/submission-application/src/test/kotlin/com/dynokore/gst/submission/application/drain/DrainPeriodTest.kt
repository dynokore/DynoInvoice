package com.dynokore.gst.submission.application.drain

import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.queue.DespatchedVersion
import com.dynokore.gst.submission.application.readiness.ReadinessCheckRecorder
import com.dynokore.gst.submission.application.readiness.ReadinessInputs
import com.dynokore.gst.submission.application.readiness.RunReadiness
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.gst.submission.application.register.UpsertResult
import com.dynokore.gst.submission.domain.drain.Tier
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.outboxandqueue.DrainOrderAssigner
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class DrainPeriodTest {
    private val sevenDays = mapOf("E1" to SafetyMarginAnswer.Known(days(7)), "E2" to SafetyMarginAnswer.Known(days(7)))

    private val dues =
        mapOf(
            ("E1" to "2026-Q1") to date("2026-12-15"),
            ("E1" to "2026-Q2") to date("2027-01-15"),
            ("E2" to "2026-Q1") to date("2026-12-01"),
            ("E2" to "2026-Q2") to date("2026-12-14"),
        )

    private fun five() =
        mutableListOf(
            DrainWorld.queued("P1", "E1", "2026-Q1", 1),
            DrainWorld.queued("P4", "E1", "2026-Q1", 2),
            DrainWorld.queued("P2", "E1", "2026-Q2", 3),
            DrainWorld.queued("P3", "E2", "2026-Q1", 4),
            DrainWorld.queued("P5", "E2", "2026-Q2", 5),
        )

    private fun world(
        margins: Map<String, SafetyMarginAnswer> = sevenDays,
        dueDates: Map<Pair<String, String>, java.time.LocalDate> = dues,
    ) = DrainWorld(five(), dueDates, margins, before = "2026-11-28", after = "2026-12-07", batchSize = 1)

    private fun DrainWorld.orderEvents() = events.filter { it.startsWith("assign") || it.startsWith("despatch") }

    @Test
    fun `INV-AC-20844 run A writes tier and rank before each despatch and despatches P3 P5 P1 P4 P2`() {
        val w = world()
        val result = w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Drained>()

        w.orderEvents() shouldBe
            listOf(
                "assign P3 1 1 DEADLINE_NEAR",
                "assign P1 2 2 null",
                "assign P4 2 3 null",
                "assign P5 3 4 null",
                "assign P2 3 5 null",
                "despatch P3",
                "assign P5 1 1 DEADLINE_NEAR",
                "assign P1 2 2 null",
                "assign P4 2 3 null",
                "assign P2 3 4 null",
                "despatch P5",
                "assign P1 2 1 null",
                "assign P4 2 2 null",
                "assign P2 3 3 null",
                "despatch P1",
                "assign P4 2 1 null",
                "assign P2 3 2 null",
                "despatch P4",
                "assign P2 3 1 null",
                "despatch P2",
            )
        w.despatched shouldBe listOf("P3", "P5", "P1", "P4", "P2")
        result.drainedCount shouldBe 5
        result.drainedByTier shouldBe mapOf(Tier.WINDOW_OPEN to 2, Tier.DRAINED_PERIOD to 2, Tier.ROUTINE to 1)
    }

    @Test
    fun `INV-AC-20844 run B a margin that is unavailable for E2 refuses before any write`() {
        val noMargin = mapOf("E1" to SafetyMarginAnswer.Known(days(7)), "E2" to SafetyMarginAnswer.Unavailable)
        val w = world(margins = noMargin)
        w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Unavailable>()
        w.allWrites shouldBe emptyList()
        w.despatched shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20844 run C a missing due date for E1 and 2026-Q2 refuses before any write`() {
        val w = world(dueDates = dues - ("E1" to "2026-Q2"))
        w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Unavailable>()
        w.allWrites shouldBe emptyList()
        w.despatched shouldBe emptyList()
    }

    @Test
    fun `the calls come in the order authorize select assign despatch residual readiness`() {
        val w = world()
        w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Drained>()
        val firsts =
            listOf("authorize", "select", "assign", "despatch", "residual", "readiness").map { name ->
                w.events.indexOfFirst { it.startsWith(name) }
            }
        firsts shouldBe firsts.sorted()
        firsts.all { it >= 0 } shouldBe true
        w.events.indexOfLast { it.startsWith("despatch") } shouldBe w.events.indexOf("residual") - 2
        w.events.last() shouldBe "readiness"
    }

    @Test
    fun `the readiness run is inside an open transaction after the despatch loop`() {
        val w = world()
        w.drain().run(w.request(HUMAN), w.deadline)
        w.readinessTxOpen shouldBe true
        w.readinessAfterDespatches shouldBe 5
        w.approvers shouldBe listOf(HUMAN.id)
    }

    @Test
    fun `a version returned twice by the despatch ends the loop`() {
        val w =
            DrainWorld(five(), dues, sevenDays, before = "2026-11-28", batchSize = 3, repeatDespatch = true)
        val result = w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Drained>()
        result.drainedCount shouldBe 1
        w.despatched.size shouldBe 2
    }

    @Test
    fun `a failed order write is Unavailable and nothing is despatched`() {
        val w = DrainWorld(five(), dues, sevenDays, failAssign = true)
        w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Unavailable>()
        w.despatched shouldBe emptyList()
    }

    @Test
    fun `a batch size of zero is refused at construction`() {
        shouldThrow<IllegalArgumentException> { DrainConfig(0) }
        shouldThrow<IllegalArgumentException> { DrainConfig(-1) }
    }

    @Test
    fun `the action keys are the two ruled constants and nothing else`() {
        DrainActions.PERSON shouldBe "period-readiness.run"
        DrainActions.MACHINE shouldBe "machine.drain.run"
        DrainActions::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.name != "INSTANCE"
            }.map { it.name }
            .sorted() shouldBe listOf("MACHINE", "PERSON")
    }

    @Test
    fun `HOURS of a margin round up to whole days`() {
        val hours = mapOf("E1" to SafetyMarginAnswer.Known(PackMargin.of(25, "HOURS")!!))
        val w =
            DrainWorld(
                mutableListOf(DrainWorld.queued("P1", "E1", "2026-Q1", 1)),
                dues,
                hours,
                before = "2026-12-13",
            )
        // 25 hours round up to 2 days: the window opens 2026-12-13, so the version is tier 1 on that date.
        w
            .drain()
            .run(w.request(HUMAN), w.deadline)
            .shouldBeInstanceOf<DrainResult.Drained>()
            .drainedByTier[Tier.WINDOW_OPEN] shouldBe 1
    }
}

internal val TENANT = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
internal val HUMAN = Principal("user-1", PrincipalKind.HUMAN)
internal val AGENT = Principal("agent-1", PrincipalKind.CONSENT_AGENT)

internal fun machineFor(entity: String): Principal =
    MachinePrincipalFixtures.machine("machine-1", TENANT, MachineEntityCoverage.Entity(entity), "drain-runner")

internal fun days(n: Long): PackMargin = PackMargin.of(n, "DAYS")!!

internal fun date(text: String): LocalDate = LocalDate.parse(text)

internal data class Write(
    val doc: String,
    val tier: Int,
    val rank: Int?,
    val lane: Lane?,
)

/**
 * The fakes of the ports of the drain, sharing one event log so that the order of the calls can be asserted. The
 * clock reads [before] until the first version is despatched and [after] from then on.
 */
@Suppress("LongParameterList")
internal class DrainWorld(
    val backlog: MutableList<QueuedVersion> = mutableListOf(),
    val dueDates: Map<Pair<String, String>, LocalDate> = emptyMap(),
    val margins: Map<String, SafetyMarginAnswer> = emptyMap(),
    before: String = "2026-11-28",
    after: String = before,
    batchSize: Int = 1,
    val allowedKeys: (String, PrincipalKind, String) -> Boolean = { _, _, _ -> true },
    val unavailableDecision: Boolean = false,
    val residualRows: List<ResidualCandidate> = emptyList(),
    val determined: Boolean = true,
    val facts: Map<String, ListFacts?> = emptyMap(),
    val failAssign: Boolean = false,
    val repeatDespatch: Boolean = false,
) {
    val events = mutableListOf<String>()
    val despatched = mutableListOf<String>()
    val allWrites = mutableListOf<Write>()
    private val passWrites = mutableListOf<Write>()
    val decisions = mutableListOf<Triple<String, PrincipalKind, String>>()
    var txOpen = false
    var residualCalledAfter: Int? = null
    val stored = mutableListOf<StoredReadinessCheck>()
    var readinessTxOpen: Boolean? = null
    var readinessAfterDespatches: Int? = null
    val approvers = mutableListOf<String>()

    private val beforeDate = date(before)
    private val afterDate = date(after)
    private val config = DrainConfig(batchSize)

    val clock: Clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant =
                (if (despatched.isEmpty()) beforeDate else afterDate).atStartOfDay(ZoneOffset.UTC).toInstant()
        }

    val deadline: Deadline = Deadline.after(Duration.ofSeconds(5), clock)

    private val authority =
        DrainAuthority { request, _ ->
            events += "authorize"
            decisions += Triple(request.action, request.principal.kind, request.entityRef)
            when {
                unavailableDecision -> {
                    AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
                }

                allowedKeys(request.action, request.principal.kind, request.entityRef) -> {
                    AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
                }

                else -> {
                    AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
                }
            }
        }

    private val dueDatePort =
        object : PeriodDueDates {
            override fun upsert(
                tenant: TenantId,
                entityRef: String,
                periodRef: String,
                dueDate: LocalDate,
                dueDateVersion: Long,
            ): UpsertResult = throw UnsupportedOperationException()

            override fun find(
                tenant: TenantId,
                entityRef: String,
                periodRef: String,
            ): LocalDate? = dueDates[entityRef to periodRef]
        }

    private val marginPort =
        SafetyMargins { _, entity, _ -> margins[entity] ?: SafetyMarginAnswer.Unavailable }

    private val backlogPort =
        DrainBacklog { tenant ->
            events += "select"
            if (tenant == TENANT) backlog.toList() else emptyList()
        }

    private val assigner =
        object : DrainOrderAssigner {
            override fun assignDrainOrder(
                partitionKey: String,
                tier: Int,
                rank: Int?,
                lane: Lane?,
            ): Int {
                check(txOpen) { "assignDrainOrder needs an open transaction" }
                if (failAssign) error("assign failed")
                events += "assign $partitionKey $tier $rank $lane"
                Write(partitionKey, tier, rank, lane).also {
                    allWrites += it
                    passWrites += it
                }
                return 1
            }
        }

    private val nextPort =
        NextDespatch {
            val chosen =
                if (repeatDespatch) {
                    backlog.firstOrNull()
                } else {
                    passWrites
                        .filter { w -> backlog.any { it.docUuid == w.doc } }
                        .minWithOrNull(compareBy<Write> { it.tier }.thenBy { it.rank })
                        ?.let { w -> backlog.first { it.docUuid == w.doc } }
                }
            chosen?.let {
                if (!repeatDespatch) backlog.remove(it)
                despatched += it.docUuid
                events += "despatch ${it.docUuid}"
                passWrites.clear()
                DespatchedVersion(it.docUuid, it.version)
            }
        }

    private val residualPort =
        ResidualSource { _, _, _ ->
            events += "residual"
            residualCalledAfter = despatched.size
            residualRows.filterNot { it.docUuid in despatched }
        }

    private val transactions =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                check(!txOpen) { "nested transaction" }
                txOpen = true
                try {
                    return block()
                } finally {
                    txOpen = false
                }
            }
        }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TENANT

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val recorder =
        object : ReadinessCheckRecorder {
            override fun append(
                entityRef: String,
                periodRef: String,
                verdict: CheckVerdict,
                checkedAt: Instant,
                approver: String,
                outcomeValues: String?,
            ): StoredReadinessCheck {
                events += "readiness"
                readinessTxOpen = txOpen
                readinessAfterDespatches = despatched.size
                approvers += approver
                return StoredReadinessCheck(
                    1,
                    "CHK-1",
                    entityRef,
                    periodRef,
                    verdict,
                    checkedAt,
                    approver,
                    outcomeValues,
                ).also { stored += it }
            }
        }

    private val entry = ReadinessEntry("D", listOf(LifecycleState.ACKNOWLEDGED), date("2026-10-15"), overridden = false)

    private val readiness =
        RunReadiness(
            { _, _ ->
                if (determined) {
                    listOf(entry)
                } else {
                    null
                }
            },
            { _, _ -> ReadinessInputs(date("2026-10-15"), CompletenessState.Passed) },
            recorder,
            clock,
        )

    private val factsPort =
        object : RegisterListFactsStore {
            override fun store(
                tenant: TenantId,
                docUuid: String,
                facts: ListFacts,
            ): Boolean = throw UnsupportedOperationException()

            override fun facts(
                tenant: TenantId,
                docUuid: String,
            ): ListFacts? = facts[docUuid]
        }

    private val selection = DrainSelection(backlogPort, dueDatePort, marginPort, clock)

    fun drain() =
        DrainPeriod(
            authority,
            selection,
            assigner,
            nextPort,
            residualPort,
            readiness,
            tenants,
            transactions,
            config,
            clock,
        )

    fun preview() = PreviewDrain(authority, selection, factsPort)

    fun request(
        principal: Principal,
        entity: String = "E1",
        period: String = "2026-Q1",
    ) = DrainRequest(TENANT, principal, entity, period)

    companion object {
        fun queued(
            doc: String,
            entity: String,
            period: String,
            minute: Long,
            version: Int = 1,
        ) = QueuedVersion(doc, version, entity, period, Instant.parse("2026-11-01T00:00:00Z").plusSeconds(60 * minute))

        fun facts(
            taxable: String,
            gst: String = "0.00",
        ) = ListFacts("N", "T", Direction.SALE, "C", "UEN", BigDecimal(taxable), BigDecimal(gst), emptySet())
    }
}
