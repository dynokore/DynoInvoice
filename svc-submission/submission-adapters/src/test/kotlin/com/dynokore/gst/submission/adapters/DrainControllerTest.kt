package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.ReadinessController
import com.dynokore.gst.submission.adapters.http.ReadinessRefusalAdvice
import com.dynokore.gst.submission.application.drain.DrainAuthority
import com.dynokore.gst.submission.application.drain.DrainBacklog
import com.dynokore.gst.submission.application.drain.DrainConfig
import com.dynokore.gst.submission.application.drain.DrainPeriod
import com.dynokore.gst.submission.application.drain.DrainSelection
import com.dynokore.gst.submission.application.drain.NextDespatch
import com.dynokore.gst.submission.application.drain.PreviewDrain
import com.dynokore.gst.submission.application.drain.QueuedVersion
import com.dynokore.gst.submission.application.drain.ResidualCandidate
import com.dynokore.gst.submission.application.drain.ResidualKind
import com.dynokore.gst.submission.application.drain.ResidualSource
import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.drain.SafetyMargins
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.queue.DespatchedVersion
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.ReadinessCheckRecorder
import com.dynokore.gst.submission.application.readiness.ReadinessInputs
import com.dynokore.gst.submission.application.readiness.RunReadiness
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.gst.submission.application.register.UpsertResult
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.outboxandqueue.DrainOrderAssigner
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.MachineTokenFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as QueueEntityRef

internal val DRAIN_TENANT = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))

/** Fakes of the ports of the drain use cases (INV-REQ-554): one tier-1 and two tier-2 documents, one residual. */
internal class DrainFixture(
    private val allow: Boolean = true,
    private val backlogReadable: Boolean = true,
) {
    val decided = mutableListOf<Pair<Principal, String>>()
    private val clock: Clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = Instant.parse("2026-11-14T00:00:00Z")
        }
    val deadline: Deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val queue =
        mutableListOf(
            QueuedVersion(
                "00000000-0000-0000-0000-0000000000a1",
                1,
                "E1",
                "2026-Q1",
                Instant.parse("2026-11-01T00:00:00Z"),
            ),
            QueuedVersion(
                "00000000-0000-0000-0000-0000000000a2",
                1,
                "E1",
                "2026-Q1",
                Instant.parse("2026-11-01T00:01:00Z"),
            ),
            QueuedVersion(
                "00000000-0000-0000-0000-0000000000b1",
                1,
                "E2",
                "2026-Q2",
                Instant.parse("2026-11-01T00:02:00Z"),
            ),
        )
    private val facts =
        mapOf(
            queue[0].docUuid to facts("100.00", "9.00"),
            queue[1].docUuid to facts("250.50", "0.00"),
            queue[2].docUuid to facts("5", "0.45"),
        )

    private val authority =
        DrainAuthority { request, _ ->
            decided += request.principal to request.action
            if (allow) {
                AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
            } else {
                AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
            }
        }
    private val dueDates =
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
            ): LocalDate? = if (periodRef == "2026-Q2") LocalDate.parse("2026-11-20") else LocalDate.parse("2026-12-31")
        }
    private val selection =
        DrainSelection(
            DrainBacklog { if (backlogReadable) queue.toList() else null },
            dueDates,
            SafetyMargins { _, _, _ -> SafetyMarginAnswer.Known(PackMargin.of(14, "DAYS")!!) },
            clock,
        )
    private val factsStore =
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
    private val assigner =
        object : DrainOrderAssigner {
            override fun assignDrainOrder(
                partitionKey: String,
                tier: Int,
                rank: Int?,
                lane: Lane?,
            ): Int = 1
        }
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = DRAIN_TENANT

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val transactions =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
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
            ) = StoredReadinessCheck(1, "CHK-1", entityRef, periodRef, verdict, checkedAt, approver, outcomeValues)
        }
    private val readiness =
        RunReadiness(
            {
                _,
                _,
                ->
                listOf(ReadinessEntry("D", listOf(LifecycleState.ACKNOWLEDGED), LocalDate.parse("2026-10-15"), false))
            },
            { _, _ -> ReadinessInputs(LocalDate.parse("2026-10-15"), CompletenessState.Passed) },
            recorder,
            clock,
        )

    fun preview() = PreviewDrain(authority, selection, factsStore)

    fun drain(): DrainPeriod {
        val next = ArrayDeque(queue)
        return DrainPeriod(
            authority,
            selection,
            assigner,
            NextDespatch { next.removeFirstOrNull()?.let { DespatchedVersion(it.docUuid, it.version) } },
            ResidualSource { _, _, _ ->
                listOf(
                    ResidualCandidate(
                        "00000000-0000-0000-0000-0000000000c1",
                        2,
                        ResidualKind.DEAD_LETTERED,
                        "SUB-001",
                        Instant.parse("2026-11-02T00:00:00Z"),
                    ),
                )
            },
            readiness,
            tenants,
            transactions,
            DrainConfig(2),
            clock,
        )
    }

    private fun facts(
        taxable: String,
        gst: String,
    ) = ListFacts("N", "T", Direction.SALE, "C", "UEN", BigDecimal(taxable), BigDecimal(gst), emptySet())
}

/**
 * A verified machine principal. The factory is internal to service-runtime and its test fixture is not on this
 * module's test classpath (build.gradle.kts is outside the task scope), so the internal constructor is called by
 * reflection: test code only, no production path does this.
 */
internal fun machinePrincipal(
    id: String,
    tenant: TenantId,
    coverage: MachineEntityCoverage,
    machineType: String,
): Principal {
    val constructor =
        Principal::class.java.declaredConstructors.single {
            it.parameterCount == 7 &&
                it.parameterTypes[1] == PrincipalKind::class.java
        }
    constructor.isAccessible = true
    return constructor.newInstance(
        id,
        PrincipalKind.MACHINE,
        tenant.takeIf {
            constructor.parameterTypes[2] == TenantId::class.java
        } ?: tenant.value,
        coverage,
        null,
        null,
        machineType,
    ) as Principal
}

class DrainControllerTest {
    private val mapper = jacksonObjectMapper()
    private val endUser =
        EndUserPrincipal("user-7", DRAIN_TENANT, setOf(QueueEntityRef("E1"), QueueEntityRef("E2")), true)
    private val machine: Principal =
        machinePrincipal("machine-9", DRAIN_TENANT, MachineEntityCoverage.Entity("E1"), "drain-runner")

    private fun controller(
        fixture: DrainFixture,
        user: EndUserPrincipal?,
        machinePrincipal: Principal?,
    ): ReadinessController {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                when (args[0]) {
                    EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE -> user
                    MachineTokenFilter.PRINCIPAL_ATTRIBUTE -> machinePrincipal
                    else -> null
                }
            } as HttpServletRequest
        return ReadinessController(
            GetLatestReadinessCheck(
                object : ReadinessCheckReader {
                    override fun latest(
                        entityRef: String,
                        periodRef: String,
                    ): StoredReadinessCheck? = null

                    override fun page(
                        entityRef: String,
                        periodRef: String,
                        limit: Int,
                        before: Long?,
                    ): List<StoredReadinessCheck> = emptyList()
                },
                OverrideAuthority { _, _, _ -> false },
            ),
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
            fixture.preview(),
            fixture.drain(),
        )
    }

    private fun <T> bound(
        fixture: DrainFixture,
        block: () -> T,
    ): T = CurrentDeadline.callWith(fixture.deadline, block)

    /** Status and body: a refusal is rendered once by [ReadinessRefusalAdvice], as the framework does. */
    private fun outcome(block: () -> ResponseEntity<*>): Pair<Int, String> {
        val thrown = runCatching(block)
        val refusal = thrown.exceptionOrNull()
        if (refusal != null) {
            val refused = refusal as? ComplianceRefusal ?: throw AssertionError("unexpected $refusal")
            val response = ReadinessRefusalAdvice().refuse(refused)
            return response.statusCode.value() to response.body!!
        }
        val response = thrown.getOrThrow()
        return response.statusCode.value() to mapper.writeValueAsString(response.body)
    }

    @Test
    fun `INV-AC-20849 the end-user preview answers 200 with the count and a two-decimal total string`() {
        val fixture = DrainFixture()
        val (status, body) =
            outcome { bound(fixture) { controller(fixture, endUser, null).previewDrainPeriod("E1", "2026-Q1") } }
        status shouldBe 200
        mapper.readTree(body).let {
            it["documentCount"].asInt() shouldBe 3
            it["totalAmount"].isString shouldBe true
            it["totalAmount"].asString() shouldBe "364.95"
        }
    }

    @Test
    fun `INV-AC-20849 the machine principal is refused the preview with SRT-004 and no count`() {
        val fixture = DrainFixture()
        val (status, body) =
            outcome { bound(fixture) { controller(fixture, null, machine).previewDrainPeriod("E1", "2026-Q1") } }
        status shouldBe 403
        body shouldContain "SRT-004"
        body shouldNotContain "documentCount"
    }

    @Test
    fun `INV-AC-20849 the drain answers 200 with every field and all three tiers for the end user and the machine`() {
        listOf<Pair<EndUserPrincipal?, Principal?>>(endUser to null, null to machine).forEach { (user, mach) ->
            val fixture = DrainFixture()
            val (status, body) =
                outcome { bound(fixture) { controller(fixture, user, mach).drainPeriod("E1", "2026-Q1") } }
            status shouldBe 200
            val json = mapper.readTree(body)
            listOf("entityRef", "periodRef", "drainedCount", "remaining", "completedAt", "residualItems", "checkId")
                .forEach { json.has(it) shouldBe true }
            json["drainedCount"].asInt() shouldBe 3
            json["checkId"].asString() shouldBe "CHK-1"
            json["residualItems"][0]["blockingReasonCode"].asString() shouldBe "SUB-001"
            (0..2).map { json["drainedByTier"][it]["tierCode"].asString() } shouldBe
                listOf("SAFETY_MARGIN", "PERIOD_BACKLOG", "ROUTINE")
            (0..2).sumOf { json["drainedByTier"][it]["drainedCount"].asInt() } shouldBe 3
        }
    }

    @Test
    fun `INV-AC-20849 a forbidden decision gives 403 SRT-004 for the preview and the drain`() {
        val fixture = DrainFixture(allow = false)
        val preview =
            outcome { bound(fixture) { controller(fixture, endUser, null).previewDrainPeriod("E1", "2026-Q1") } }
        val drain = outcome { bound(fixture) { controller(fixture, null, machine).drainPeriod("E1", "2026-Q1") } }
        listOf(preview, drain).forEach {
            it.first shouldBe 403
            it.second shouldContain "SRT-004"
        }
    }

    @Test
    fun `INV-AC-20849 unreadable data gives the closed unavailable case SRT-013 with no partial body`() {
        val fixture = DrainFixture(backlogReadable = false)
        val preview =
            outcome { bound(fixture) { controller(fixture, endUser, null).previewDrainPeriod("E1", "2026-Q1") } }
        val drain = outcome { bound(fixture) { controller(fixture, endUser, null).drainPeriod("E1", "2026-Q1") } }
        listOf(preview, drain).forEach {
            it.first shouldBe 503
            it.second shouldContain "SRT-013"
            it.second shouldNotContain "documentCount"
            it.second shouldNotContain "drainedCount"
        }
    }

    @Test
    fun `INV-AC-20849 the principal handed to the use case is the verified one and never built from a role`() {
        val human = DrainFixture()
        bound(human) { controller(human, endUser, null).drainPeriod("E1", "2026-Q1") }
        human.decided.map { it.first.id to it.first.kind }.distinct() shouldBe listOf("user-7" to PrincipalKind.HUMAN)
        val mach = DrainFixture()
        bound(mach) { controller(mach, null, machine).drainPeriod("E1", "2026-Q1") }
        mach.decided.map { it.first }.distinct() shouldBe listOf(machine)
        val none =
            outcome { bound(DrainFixture()) { controller(DrainFixture(), null, null).drainPeriod("E1", "2026-Q1") } }
        none.first shouldBe 401
    }

    @Test
    fun `the other operations of the readiness interface still answer 501`() {
        val fixture = DrainFixture()
        val controller = controller(fixture, endUser, null)
        controller.getReadiness("E1", "2026-Q1").statusCode.value() shouldBe 501
    }
}
