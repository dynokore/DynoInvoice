package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.application.DryRunPermissionPort
import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.PeriodCalendarUnavailableException
import com.dynokore.gst.rules.application.SamplePage
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

private const val CONSOLE_BFF = "console-bff"
private const val SUFFIX_ALT = "-alt"
private const val DRY_RUNS = "/internal/v1/rules/packs"
private const val ENTITY_E1 = "E-1"
private const val USER_B_BEARER = "Bearer token-user-b"
private val CANDIDATE_LABELS: MutableSet<String> = ConcurrentHashMap.newKeySet()
private const val CURRENT_PREFIX = "cur-4974"
private const val REQUESTED = "com.dynokore.gst.platform.PackDryRunSampleRequested.v1"
private const val SRT_001 = "SRT-001"
private const val SRT_013 = "SRT-013"
private const val POLL_MILLIS = 100L
private const val POLL_LIMIT = 100
private const val SLOW_RUN_MILLIS = 2600L
private const val D0 = "0d000000-0000-4000-8000-000000000000"
private const val D1 = "0d000000-0000-4000-8000-000000000001"
private const val D2 = "0d000000-0000-4000-8000-000000000002"
private const val D3 = "0d000000-0000-4000-8000-000000000003"
private val OWNED_BY_THE_RUN =
    setOf("dry_run_delta", "dry_run_delta_document", "dry_run_delta_total", "outbox")

/** Counts the calls of the allowing test permission port (the permission is not what these tests assert). */
object DryRunPermissionProbe {
    val calls =
        java.util.concurrent.atomic
            .AtomicInteger()
}

/** An allowing [DryRunPermissionPort] for the contexts whose assertions are not about the permission (INV-T-22819). */
@TestConfiguration(proxyBeanMethods = false)
class DryRunAllowingPermission {
    @Bean
    @org.springframework.context.annotation.Primary
    fun allowingDryRunPermission(): DryRunPermissionPort =
        DryRunPermissionPort {
            DryRunPermissionProbe.calls.incrementAndGet()
            true
        }
}

/** Canned verdicts of the test composition: the current pack answers `SR`; the candidate answers [candidate]. */
class FixtureEvaluation : PackEvaluationPort {
    val evaluated = CopyOnWriteArrayList<String>()
    val candidate = ConcurrentHashMap<String, DocumentOutcome>()

    override fun evaluate(
        tenant: TenantId,
        packType: String,
        packVersion: String,
        document: SampledDocument,
    ): DocumentOutcome {
        evaluated.add(document.docUuid)
        return if (packVersion in CANDIDATE_LABELS) {
            candidate[document.docUuid] ?: DocumentOutcome("SR")
        } else {
            DocumentOutcome("SR")
        }
    }

    override fun effectiveFrom(
        tenant: TenantId,
        packType: String,
        packVersion: String,
    ): java.time.LocalDate = java.time.LocalDate.of(2026, 1, 1)

    fun reset() {
        evaluated.clear()
        candidate.clear()
    }
}

/** The fixture adapters of the test composition; they back off the event adapter and the default calendar. */
@TestConfiguration(proxyBeanMethods = false)
@Import(MappingAllowingPorts::class, MappingDoubles::class, DryRunAllowingPermission::class)
class DryRunFixtures {
    @Bean fun fixtureSamples() = FixtureHistoricalSamplePort()

    @Bean fun fixturePeriods() = FixtureLastCompletePeriodPort()

    @Bean fun fixtureEvaluation() = FixtureEvaluation()

    @Bean fun clock(): SwitchableClock = SwitchableClock()
}

/** The system clock until a test pins it to an instant (the day with no mapping pack in force). */
class SwitchableClock : java.time.Clock() {
    @Volatile var pinned: java.time.Instant? = null

    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): java.time.Clock = this

    override fun instant(): java.time.Instant = pinned ?: java.time.Instant.now()
}

/** The fixture samples and evaluation, and the default period calendar (no `LastCompletePeriodPort` bean). */
@TestConfiguration(proxyBeanMethods = false)
@Import(MappingAllowingPorts::class, MappingDoubles::class, DryRunAllowingPermission::class)
class DryRunFixturesWithoutCalendar {
    @Bean fun fixtureSamples() = FixtureHistoricalSamplePort()

    @Bean fun fixtureEvaluation() = FixtureEvaluation()
}

/** Removes the event adapter of the sample (slice 3) from the context: the production case before canonical-svc. */
@TestConfiguration(proxyBeanMethods = false)
@Import(MappingAllowingPorts::class, MappingDoubles::class, DryRunAllowingPermission::class)
class DryRunWithoutSampleAdapter {
    @Bean fun fixtureEvaluation() = FixtureEvaluation()

    @Bean
    fun withoutEventAdapter(): BeanDefinitionRegistryPostProcessor =
        object : BeanDefinitionRegistryPostProcessor {
            override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
                if (registry.containsBeanDefinition("historicalSamplePort")) {
                    registry.removeBeanDefinition("historicalSamplePort")
                }
            }

            override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
        }
}

private const val WAIT_PROPERTY = "rules.dry-run.sample-wait=PT2S"
private const val IDEMPOTENCY = "service-runtime.idempotency.schema=rules"
private const val CALLER_0 = "service-runtime.workload-identity.callers[0].service=$CONSOLE_BFF"
private const val SUBJECT_0 = "service-runtime.workload-identity.callers[0].subject=subject-of-$CONSOLE_BFF"
private const val CALLER_1 = "service-runtime.workload-identity.callers[1].service=$CONSOLE_BFF$SUFFIX_ALT"
private const val SUBJECT_1 = "service-runtime.workload-identity.callers[1].subject=subject-of-$CONSOLE_BFF$SUFFIX_ALT"

/** What the three contexts share: the calls, the pack fixtures and the comparisons of every table of `rules`. */
open class DryRunSupport(
    port: Int,
) : MappingPackSupport(port) {
    private var counter = 0

    protected fun label() = "2026.d${++counter}-${UUID.randomUUID().toString().take(6)}"

    /**
     * One ARMED mapping pack of tenant A, so that a candidate has a current pack to be compared with. It is effective
     * from yesterday: after every date the other test classes of the shared database use, and never after the clock.
     */
    protected fun ensureCurrentPack() {
        if (!currentPack) {
            synchronized(DryRunSupport::class.java) {
                if (!currentPack) {
                    val from =
                        java.time.LocalDate
                            .now(java.time.ZoneOffset.UTC)
                            .minusDays(1)
                            .toString()
                    fullFlow("$CURRENT_PREFIX-${UUID.randomUUID().toString().take(6)}", from)
                    currentPack = true
                }
            }
        }
    }

    protected fun candidate(label: String = label()): String {
        ensureCurrentPack()
        CANDIDATE_LABELS.add(label)
        val packId = draft(label)
        ok(mapCode(packId), 201)
        return packId
    }

    protected fun dryRun(
        packId: String,
        body: String? = null,
        user: String? = com.dynokore.gst.rules.served.USER_A_TOKEN,
        workload: String? = workloadToken(CONSOLE_BFF),
    ): HttpResponse<String> = call("POST", "$DRY_RUNS/$packId/dry-runs", body, user, workload)

    protected fun getRun(
        packId: String,
        dryRunId: String,
        user: String? = com.dynokore.gst.rules.served.USER_A_TOKEN,
        workload: String? = workloadToken(CONSOLE_BFF),
    ): HttpResponse<String> = call("GET", "$DRY_RUNS/$packId/dry-runs/$dryRunId", null, user, workload)

    /** Calls `dryRun` and, when it answers 202, polls its status resource until the run has ended. */
    protected fun finished(
        packId: String,
        body: String? = null,
    ): JsonNode {
        val answer = dryRun(packId, body)
        withClue(answer.body()) { (answer.statusCode() == 200 || answer.statusCode() == 202) shouldBe true }
        val node = json(answer)
        if (answer.statusCode() == 200) {
            val id = lastRunId(packId)
            return json(getRun(packId, id))
        }
        val id = node.get("dryRunId").asString()
        repeat(POLL_LIMIT) {
            val status = json(getRun(packId, id))
            if (status.get("state").asString() != "RUNNING") return status
            Thread.sleep(POLL_MILLIS)
        }
        error("the dry run $id did not end")
    }

    /** Polls the status resource until the run has ended and answers the last raw response. */
    protected fun pollRaw(
        packId: String,
        id: String,
    ): HttpResponse<String> {
        repeat(POLL_LIMIT) {
            val read = getRun(packId, id)
            if (read.statusCode() != 200 || json(read).get("state").asString() != "RUNNING") return read
            Thread.sleep(POLL_MILLIS)
        }
        error("the dry run $id did not end")
    }

    protected fun pollEnd(
        packId: String,
        id: String,
    ): JsonNode = json(pollRaw(packId, id))

    protected fun lastRunId(packId: String): String =
        value(
            TENANT_A,
            "select d.id::text from rules.dry_run_delta d join rules.pack_registry r " +
                "on r.pack_version = d.pack_version " +
                "and r.pack_type = d.pack_type and r.tenant_id = d.tenant_id where r.pack_id = ? " +
                "order by d.started_at desc limit 1",
            packId,
        ).toString()

    /** The payload of the request events written for the candidate version [label], oldest first. */
    protected fun requested(label: String): List<JsonNode> =
        rows(TENANT_A, "select data::text as d from rules.outbox where type = '$REQUESTED' order by id")
            .map { json(it.getValue("d").toString()) }
            .filter { it.get("candidatePackVersion").asString() == label }

    private fun json(text: String): JsonNode = mapper.readTree(text)

    protected fun elements(array: JsonNode): List<JsonNode> = (0 until array.size()).map { array.get(it) }

    protected fun windowOf(label: String): Triple<String, String, Int> {
        val event = requested(label).single()
        return Triple(
            event.get("firstPeriodRef").asString(),
            event.get("lastPeriodRef").asString(),
            event.get("periodCount").asInt(),
        )
    }

    protected fun refused(
        answer: HttpResponse<String>,
        status: Int,
        code: String,
        before: Map<String, List<String>>,
    ) {
        withClue(answer.body()) {
            answer.statusCode() shouldBe status
            json(answer).get("code").asString() shouldBe code
        }
        snapshot() shouldBe before
    }

    protected fun item(
        doc: String,
        period: String,
        taxable: String = "100.00",
        gst: String = "9.00",
    ): Map<String, Any?> =
        mapOf(
            "periodRef" to period,
            "docUuid" to doc,
            "entityRef" to ENTITY_E1,
            "transactionDate" to "2026-07-01",
            "internalTaxCode" to "STD",
            "documentFunction" to "INVOICE",
            "supplyType" to "STANDARD_RATED",
            "taxableValue" to taxable,
            "gstValue" to gst,
        )

    protected fun window(
        first: String,
        last: String,
        count: Int,
    ) = """{"firstPeriodRef":"$first","lastPeriodRef":"$last","periodCount":$count}"""

    companion object {
        @Volatile private var currentPack = false

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = registerCatalogue(registry)
    }
}

/**
 * `dryRun` and `getDryRun` at the HTTP boundary with the fixture adapters and the real database (INV-T-4974;
 * INV-REQ-772, INV-REQ-1303, INV-REQ-1352).
 */
@Suppress("LargeClass") // one boundary class per task keeps the fixtures and the acceptance keys together
@SpringBootTest(
    classes = [DryRunFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [WAIT_PROPERTY, IDEMPOTENCY, CALLER_0, SUBJECT_0, CALLER_1, SUBJECT_1],
)
class DryRunBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired private val samples: FixtureHistoricalSamplePort,
    @Autowired private val periods: FixtureLastCompletePeriodPort,
    @Autowired private val evaluation: FixtureEvaluation,
    @Autowired private val clock: SwitchableClock,
) : DryRunSupport(port) {
    @BeforeEach
    @AfterEach
    fun reset() {
        clock.pinned = null
        samples.clear()
        periods.reset()
        evaluation.reset()
    }

    // ---- the window of a call (INV-AC-10766) ----

    @Test
    fun `INV-AC-10766 no body records first period P, last period P and one period`() {
        val label = label()
        val packId = candidate(label)
        finished(packId)
        windowOf(label) shouldBe Triple("2026-Q3", "2026-Q3", 1)
        periods.calls.get() shouldBe 1
    }

    private fun recordedAsGiven(count: Int) {
        val label = label()
        val packId = candidate(label)
        val body = window("2026-Q${4 - count}", "2026-Q3", count)
        val answer = dryRun(packId, body)
        withClue(answer.body()) { (answer.statusCode() == 200 || answer.statusCode() == 202) shouldBe true }
        windowOf(label) shouldBe Triple("2026-Q${4 - count}", "2026-Q3", count)
        periods.calls.get() shouldBe 0
    }

    @Test
    fun `INV-AC-10766 a window of 1 period is recorded as given`() = recordedAsGiven(1)

    @Test
    fun `INV-AC-10766 a window of 2 periods is recorded as given`() = recordedAsGiven(2)

    @Test
    fun `INV-AC-10766 a window of 3 periods is recorded as given`() = recordedAsGiven(3)

    @Test
    fun `INV-AC-10766 a window of 4 periods is recorded as given`() = recordedAsGiven(4)

    @Test
    fun `INV-AC-10766 a window of 5 periods is recorded as given and not narrowed`() = recordedAsGiven(5)

    @Test
    fun `INV-AC-10766 periodCount 0 is refused 400 SRT-001 and stores nothing`() {
        val packId = candidate()
        val before = snapshot()
        refused(dryRun(packId, window("2026-Q3", "2026-Q3", 0)), 400, SRT_001, before)
    }

    @Test
    fun `INV-AC-10766 a partial window is refused 400 SRT-001 and stores nothing`() {
        val packId = candidate()
        val before = snapshot()
        refused(dryRun(packId, """{"firstPeriodRef":"2026-Q3"}"""), 400, SRT_001, before)
        refused(dryRun(packId, """{"periodCount":2}"""), 400, SRT_001, before)
        refused(dryRun(packId, "{}"), 400, SRT_001, before)
    }

    @Test
    fun `INV-AC-10766 a window of 5 periods against three served periods ends without a sample and not COMPLETE`() {
        val packId = candidate()
        val status = finished(packId, window("2025-Q4", "2026-Q3", 5))
        status.get("state").asString() shouldBe "FAILED"
        status.get("error").get("detail").asString() shouldContain "sample incomplete"
    }

    // ---- the period source of the default window (INV-AC-10766) ----

    @Test
    fun `INV-AC-10766 a fixture answering Q records Q and never a locally computed period`() {
        periods.answer = "2025-Q1"
        val label = label()
        finished(candidate(label))
        windowOf(label) shouldBe Triple("2025-Q1", "2025-Q1", 1)
    }

    @Test
    fun `INV-AC-10766 a period calendar answering null refuses 503 SRT-013 and stores nothing`() {
        val packId = candidate()
        periods.answer = null
        val before = snapshot()
        refused(dryRun(packId), 503, SRT_013, before)
    }

    @Test
    fun `INV-AC-10766 a period calendar that is unavailable refuses 503 SRT-013 and stores nothing`() {
        val packId = candidate()
        periods.unavailable = true
        val before = snapshot()
        val answer = dryRun(packId)
        refused(answer, 503, SRT_013, before)
        answer.body() shouldContain "period calendar"
    }

    // ---- INV-AC-2290: the last complete period, and not the one before ----

    @Test
    fun `INV-AC-2290 the dry run evaluates every document of the last complete period and none of the period before`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3"), item(D2, "2026-Q3")))
        samples.serve("2026-Q2", listOf(item(D0, "2026-Q2")))
        val status = finished(candidate())
        status.get("state").asString() shouldBe "SUCCEEDED"
        evaluation.evaluated.toSet() shouldBe setOf(D1, D2)
        evaluation.evaluated.contains(D0) shouldBe false
    }

    // ---- approval needs the stored delta (INV-AC-1725, INV-AC-1783, INV-AC-10767, INV-AC-10770) ----

    @Test
    fun `INV-AC-1725 a pack submitted with no dry run completed is refused at approval`() {
        val packId = seedPack("nd-${UUID.randomUUID().toString().take(6)}", "SUBMITTED")
        val before = snapshot()
        refused(approve(packId), 409, "SRT-008", before)
    }

    @Test
    fun `INV-AC-1783 after the dry run approval signs the stored delta and no other state changed`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3", "1000.00", "90.00"), item(D3, "2026-Q3")))
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        val label = label()
        val packId = candidate(label)
        val before = snapshot()
        val status = finished(packId)
        status.get("state").asString() shouldBe "SUCCEEDED"
        val changed = changedTables(before, snapshot())
        withClue("tables the dry run changed: $changed") {
            changed.filterNot { it in OWNED_BY_THE_RUN || it.contains("idempotency") } shouldBe emptyList()
        }
        ok(submit(packId))
        ok(approve(packId))
        val deltaId = lastRunId(packId)
        rows(
            TENANT_A,
            "select dry_run_delta_id::text as i from rules.mapping_pack_approval where pack_version = '$label'",
        ).single()["i"] shouldBe deltaId
        val stored =
            rows(
                TENANT_A,
                "select doc_uuid::text as d, old_code, new_code, taxable_value::text as t, gst_value::text as g " +
                    "from rules.dry_run_delta_document where delta_id = '$deltaId'::uuid",
            )
        stored.map { it["d"] } shouldBe listOf(D1)
        stored.single()["old_code"] shouldBe "SR"
        stored.single()["new_code"] shouldBe "ZR"
        stored.single()["t"] shouldBe "1000.00"
        stored.single()["g"] shouldBe "90.00"
    }

    private fun changedTables(
        before: Map<String, List<String>>,
        after: Map<String, List<String>>,
    ) = after.keys.filter { after[it] != before[it] }

    @Test
    fun `INV-AC-10767 the stored delta lists D1 and D2 and not D3 and approval of P1 succeeds while P2 is refused`() {
        samples.serve(
            "2026-Q3",
            listOf(
                item(D1, "2026-Q3", "1000.00", "90.00"),
                item(D2, "2026-Q3", "500.00", "45.00"),
                item(D3, "2026-Q3"),
            ),
        )
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        evaluation.candidate[D2] = DocumentOutcome("RUL-002", "BLOCK")
        val p1 = candidate()
        val result = finished(p1).get("result")
        result.get("changedCount").asInt() shouldBe 2
        elements(result.get("sample")).map { it.get("docUuid").asString() }.toSet() shouldBe setOf(D1, D2)
        val d1 = elements(result.get("sample")).first { it.get("docUuid").asString() == D1 }
        d1.get("oldCategoryCode").asString() shouldBe "SR"
        d1.get("newCategoryCode").asString() shouldBe "ZR"
        d1.get("taxableValue").asString() shouldBe "1000.00"
        d1.get("gstValue").asString() shouldBe "90.00"
        val totals = elements(result.get("changeTypeTotals"))
        totals.map { it.get("changeType").asString() } shouldBe
            listOf("CODE_CHANGED", "NEWLY_REFUSED", "NEWLY_ACCEPTED")
        totals.sumOf { it.get("documentCount").asInt() } shouldBe 2
        ok(submit(p1))
        val approved = ok(approve(p1))
        elements(approved.get("approvals")).last().get("approvedBy").asString() shouldBe "user-b"
        // the look-alike P2: submitted with no dry run, refused
        val p2 = seedPack("p2-${UUID.randomUUID().toString().take(6)}", "SUBMITTED")
        refused(approve(p2), 409, "SRT-008", snapshot())
    }

    @Test
    fun `INV-AC-10770 a mapping pack change lists the changed document only and P3 with no dry run is refused`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3", "250.00", "22.50"), item(D3, "2026-Q3")))
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        val result = finished(candidate()).get("result")
        result.get("changedCount").asInt() shouldBe 1
        elements(result.get("sample")).map { it.get("docUuid").asString() } shouldBe listOf(D1)
        elements(result.get("sample")).single().get("taxableValue").asString() shouldBe "250.00"
        elements(result.get("sample")).single().get("gstValue").asString() shouldBe "22.50"
        elements(result.get("changeTypeTotals")).sumOf { it.get("documentCount").asInt() } shouldBe 1
        val p3 = seedPack("p3-${UUID.randomUUID().toString().take(6)}", "SUBMITTED")
        refused(approve(p3), 409, "SRT-008", snapshot())
    }

    // ---- a mapping pack with no pack in force (Inference: SRT-008) ----

    @Test
    fun `a tenant with no mapping pack in force is refused 409 SRT-008 and a day with one is accepted`() {
        val packId = candidate()
        val before = snapshot()
        clock.pinned = java.time.Instant.parse("2000-01-01T00:00:00Z")
        val refusal = dryRun(packId)
        refused(refusal, 409, "SRT-008", before)
        json(refusal).get("detail").asString() shouldBe "no mapping pack is in force to compare the candidate with"
        clock.pinned = null
        val accepted = dryRun(packId)
        withClue(accepted.body()) { (accepted.statusCode() == 200 || accepted.statusCode() == 202) shouldBe true }
    }

    // ---- the end time of a run (contract: completedAt present when the state is not RUNNING) ----

    @Test
    fun `a run that ends SAMPLE_UNAVAILABLE reads FAILED with completedAt not before startedAt`() {
        samples.pagesOverride = listOf(SamplePage(2, true, listOf(item(D1, "2026-Q3"))))
        val packId = candidate()
        val status = finished(packId)
        status.get("state").asString() shouldBe "FAILED"
        val started = java.time.OffsetDateTime.parse(status.get("startedAt").asString())
        val completed = java.time.OffsetDateTime.parse(status.get("completedAt").asString())
        (completed.isBefore(started)) shouldBe false
        value(
            TENANT_A,
            "select count(*) from rules.dry_run_delta where id = ?::uuid and completed_at is not null",
            status.get("dryRunId").asString(),
        ).toString() shouldBe "1"
    }

    @Test
    fun `a COMPLETE run keeps the completedAt of complete and a RUNNING run has none`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3")))
        samples.delayMillis = SLOW_RUN_MILLIS
        val packId = candidate()
        val running = dryRun(packId)
        running.statusCode() shouldBe 202
        val node = json(running)
        node.get("state").asString() shouldBe "RUNNING"
        node.has("completedAt") shouldBe false
        val id = node.get("dryRunId").asString()
        val status = pollEnd(packId, id)
        status.get("state").asString() shouldBe "SUCCEEDED"
        value(
            TENANT_A,
            "select count(*) from rules.dry_run_delta where id = ?::uuid and " +
                "completed_at = ?::timestamptz",
            id,
            status.get("completedAt").asString(),
        ).toString() shouldBe "1"
    }

    // ---- a changed document with no supply type fails closed ----

    private fun assertFailedNoSupplyType(
        packId: String,
        page: Int,
        entity: String,
    ) {
        val status = finished(packId)
        status.get("state").asString() shouldBe "FAILED"
        status.has("result") shouldBe false
        status.get("error").get("code").asString() shouldBe "SRT-013"
        val detail = status.get("error").get("detail").asString()
        detail shouldContain status.get("dryRunId").asString()
        detail shouldContain "page $page,"
        detail shouldContain "entityRef $entity"
        val id = status.get("dryRunId").asString()
        value(
            TENANT_A,
            "select count(*) from rules.dry_run_delta_document where delta_id = ?::uuid",
            id,
        ).toString() shouldBe
            "0"
        value(
            TENANT_A,
            "select count(*) from rules.dry_run_delta_total where delta_id = ?::uuid",
            id,
        ).toString() shouldBe
            "0"
        value(TENANT_A, "select state from rules.dry_run_delta where id = ?::uuid", id).toString() shouldBe
            "SAMPLE_UNAVAILABLE"
        submit(packId).statusCode() shouldBe 409
    }

    @Test
    fun `INV-AC-20340 a changed document with no supply type ends FAILED with SRT-013 and no stored delta`() {
        samples.serve("2026-Q3", listOf((item(D1, "2026-Q3") - "supplyType") + ("entityRef" to "E-9")))
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        assertFailedNoSupplyType(candidate(), 1, "E-9")
    }

    @Test
    fun `INV-AC-20340 the same item unchanged lets the run complete`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3") - "supplyType"))
        val status = finished(candidate())
        status.get("state").asString() shouldBe "SUCCEEDED"
        status.get("result").get("changedCount").asInt() shouldBe 0
    }

    @Test
    fun `INV-AC-20340 two items without supplyType on pages 1 and 2 name the first in page order`() {
        samples.pagesOverride =
            listOf(
                SamplePage(2, true, listOf((item(D2, "2026-Q3") - "supplyType") + ("entityRef" to "E-2"))),
                SamplePage(1, false, listOf((item(D1, "2026-Q3") - "supplyType") + ("entityRef" to "E-1"))),
            )
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        evaluation.candidate[D2] = DocumentOutcome("ZR")
        assertFailedNoSupplyType(candidate(), 1, "E-1")
    }

    @Test
    fun `INV-AC-20340 the member supplyTypes in place of supplyType on a changed item counts as missing`() {
        samples.serve(
            "2026-Q3",
            listOf((item(D1, "2026-Q3") - "supplyType") + ("supplyTypes" to "STANDARD_RATED") + ("entityRef" to "E-3")),
        )
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        assertFailedNoSupplyType(candidate(), 1, "E-3")
    }

    @Test
    fun `INV-AC-20340 an empty string supplyType on a changed item counts as missing`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3") + ("supplyType" to "") + ("entityRef" to "E-4")))
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        assertFailedNoSupplyType(candidate(), 1, "E-4")
    }

    @Test
    fun `a changed document with the supply type STANDARD_RATED answers 200 with totals adding up to changedCount`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3")))
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        val result = finished(candidate()).get("result")
        result.get("changedCount").asInt() shouldBe 1
        elements(result.get("supplyTypeTotals")).sumOf { it.get("documentCount").asInt() } shouldBe 1
    }

    // ---- supplyType and supplyTypeTotals over HTTP (INV-T-10748, INV-Q-10703 B') ----

    private fun changed(vararg docs: Pair<String, Map<String, Any?>>): JsonNode {
        samples.serve("2026-Q3", docs.map { it.second })
        docs.forEach { evaluation.candidate[it.first] = DocumentOutcome("ZR") }
        return finished(candidate()).get("result")
    }

    private fun typeOf(
        type: String,
        doc: String,
        taxable: String = "100.00",
        gst: String = "9.00",
    ) = doc to (item(doc, "2026-Q3", taxable, gst) + ("supplyType" to type))

    private fun JsonNode.totalsList() =
        elements(get("supplyTypeTotals")).map {
            listOf(
                it.get("supplyType").asString(),
                it.get("documentCount").asInt(),
                it.get("taxableValue").asString(),
                it.get("gstValue").asString(),
                it.get("valuesAbsentCount").asInt(),
            )
        }

    @Test
    fun `INV-AC-9558 supplyTypeTotals over two changed types give the criterion numbers and name no identifier`() {
        val unchanged =
            listOf(
                item(D3, "2026-Q3") + ("supplyType" to "EXEMPT"),
                item(D0, "2026-Q3") + ("supplyType" to "IMPORTS"),
            )
        val one = typeOf("STANDARD_RATED", D1, "1000.00", "90.00")
        val two = typeOf("ZERO_RATED", D2, "250.00", "22.50")
        samples.serve("2026-Q3", listOf(one.second, two.second) + unchanged)
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        evaluation.candidate[D2] = DocumentOutcome("ZR")
        val result = finished(candidate()).get("result")
        result.totalsList() shouldBe
            listOf(listOf("STANDARD_RATED", 1, "1000.00", "90.00", 0), listOf("ZERO_RATED", 1, "250.00", "22.50", 0))
        elements(result.get("supplyTypeTotals")).all {
            it.propertyNames().asSequence().toSet() ==
                setOf("supplyType", "documentCount", "taxableValue", "gstValue", "valuesAbsentCount")
        } shouldBe true
    }

    @Test
    fun `an unknown member and the look-alikes are carried unchanged, listed after the five in string order`() {
        val result =
            changed(
                typeOf("ZERO_RATED", D0),
                typeOf("DEEMED_SUPPLY", D1),
                typeOf("zero_rated", D2),
                typeOf("ZERO-RATED", D3),
            )
        result.totalsList().map { it[0] } shouldBe listOf("ZERO_RATED", "DEEMED_SUPPLY", "ZERO-RATED", "zero_rated")
        elements(result.get("sample")).map { it.get("supplyType").asString() }.toSet() shouldBe
            setOf("ZERO_RATED", "DEEMED_SUPPLY", "zero_rated", "ZERO-RATED")
        elements(result.get("supplyTypeTotals")).sumOf { it.get("documentCount").asInt() } shouldBe
            result.get("changedCount").asInt()
    }

    @Test
    fun `each sample item carries the supplyType of its page item and the totals cross-check the change types`() {
        val result = changed(typeOf("EXEMPT", D1, "5.00", "0.00"), typeOf("IMPORTS", D2, "7.50", "0.68"))
        elements(
            result.get("sample"),
        ).associate { it.get("docUuid").asString() to it.get("supplyType").asString() } shouldBe
            mapOf(D1 to "EXEMPT", D2 to "IMPORTS")

        fun sum(
            array: String,
            member: String,
        ) = elements(result.get(array)).sumOf { java.math.BigDecimal(it.get(member).asString()) }
        sum("supplyTypeTotals", "taxableValue") shouldBe sum("changeTypeTotals", "taxableValue")
        sum("supplyTypeTotals", "gstValue") shouldBe sum("changeTypeTotals", "gstValue")
    }

    @Test
    fun `a flagged document is listed with its type and 0 00, counted, and left out of both sums`() {
        val flagged = item(D1, "2026-Q3", "777.00", "70.00") + ("sgdValuesAbsent" to true)
        val result = changed(D1 to flagged, typeOf("STANDARD_RATED", D2, "100.00", "9.00"))
        result.totalsList() shouldBe listOf(listOf("STANDARD_RATED", 2, "100.00", "9.00", 1))
        val listed = elements(result.get("sample")).first { it.get("docUuid").asString() == D1 }
        listed.get("supplyType").asString() shouldBe "STANDARD_RATED"
        listed.get("sgdValuesAbsent").asBoolean() shouldBe true
        listed.get("taxableValue").asString() shouldBe "0.00"
        listed.get("gstValue").asString() shouldBe "0.00"
        elements(result.get("sample")).first { it.get("docUuid").asString() == D2 }.has("sgdValuesAbsent") shouldBe
            false
    }

    @Test
    fun `a delta that changes no document gives an empty supplyTypeTotals`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3")))
        val result = finished(candidate()).get("result")
        result.get("changedCount").asInt() shouldBe 0
        result.get("supplyTypeTotals").size() shouldBe 0
    }

    // ---- 202, the status resource and who may read it (INV-API-3473, INV-TS-3474) ----

    @Test
    fun `a run that outlasts 2000 ms answers 202 with the status resource and only the starting principal reads it`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3")))
        samples.delayMillis = SLOW_RUN_MILLIS
        val packId = candidate()
        val answer = dryRun(packId)
        answer.statusCode() shouldBe 202
        val node = json(answer)
        val id = node.get("dryRunId").asString()
        node.get("state").asString() shouldBe "RUNNING"
        answer.headers().firstValue("Location").get() shouldBe "$DRY_RUNS/$packId/dry-runs/$id"
        answer.headers().firstValue("Retry-After").isPresent shouldBe true
        val other = getRun(packId, id, USER_B_BEARER)
        other.statusCode() shouldBe 404
        json(other).get("code").asString() shouldBe "SRT-005"
        getRun(packId, UUID.randomUUID().toString()).statusCode() shouldBe 404
        repeat(POLL_LIMIT) {
            if (json(getRun(packId, id)).get("state").asString() != "RUNNING") return
            Thread.sleep(POLL_MILLIS)
        }
        error("the slow dry run did not end")
    }

    @Test
    fun `a page missing before the last page ends the run without a sample and approval is refused`() {
        samples.pagesOverride = listOf(SamplePage(2, true, listOf(item(D1, "2026-Q3"))))
        val label = label()
        val packId = candidate(label)
        val status = finished(packId)
        status.get("state").asString() shouldBe "FAILED"
        status.get("error").get("detail").asString() shouldContain "page 1 is missing"
        submit(packId).statusCode() shouldBe 409
    }

    // ---- a refused caller leaves nothing stored (INV-Q-12122) ----

    @Test
    fun `dryRun refuses a caller the edge does not name and leaves nothing stored`() {
        val packId = candidate()
        val before = snapshot()
        val used = samples.windows.size
        val wrong = dryRun(packId, workload = workloadToken(CONSOLE_BFF + SUFFIX_ALT))
        wrong.statusCode() shouldBe 403
        wrong.body() shouldContain "SRT-004"
        dryRun(packId, workload = null).statusCode() shouldBe 401
        samples.windows.size shouldBe used
        periods.calls.get() shouldBe 0
        snapshot() shouldBe before
        // the named caller reaches the use case: the refusal is specific to the caller
        dryRun(packId).statusCode() shouldBe 200
        periods.calls.get() shouldBe 1
    }

    @Test
    fun `getDryRun refuses a caller the edge does not name and leaves nothing stored`() {
        val packId = candidate()
        val id = json(getRun(packId, finishedId(packId))).get("dryRunId").asString()
        val before = snapshot()
        val wrong = getRun(packId, id, workload = workloadToken(CONSOLE_BFF + SUFFIX_ALT))
        wrong.statusCode() shouldBe 403
        wrong.body() shouldContain "SRT-004"
        getRun(packId, id, workload = null).statusCode() shouldBe 401
        snapshot() shouldBe before
        getRun(packId, id).statusCode() shouldBe 200
    }

    private fun finishedId(packId: String): String = finished(packId).get("dryRunId").asString()

    @Test
    fun `probe - the comparison can fail around a call of the named caller that writes one outbox row`() {
        val packId = candidate()
        val before = snapshot()
        dryRun(packId).statusCode() shouldBe 200
        val after = snapshot()
        (after == before) shouldBe false
        after.getValue("outbox").size shouldBe before.getValue("outbox").size + 1
    }

    @Test
    fun `a no-body call and an explicit window write exactly one request each`() {
        val label = label()
        val packId = candidate(label)
        dryRun(packId).statusCode() shouldBe 200
        dryRun(packId, window("2026-Q2", "2026-Q3", 2))
        requested(label).size shouldBe 2
    }
}

/** The default period calendar (no `LastCompletePeriodPort` bean): a body-less call is refused, a window is served. */
@SpringBootTest(
    classes = [DryRunFixturesWithoutCalendar::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [WAIT_PROPERTY, IDEMPOTENCY, CALLER_0, SUBJECT_0, CALLER_1, SUBJECT_1],
)
class DryRunDefaultCalendarTest(
    @LocalServerPort port: Int,
    @Autowired private val context: ApplicationContext,
) : DryRunSupport(port) {
    @Test
    fun `INV-AC-10766 the default binding refuses a body-less call 503 SRT-013 naming the period calendar`() {
        val packId = candidate()
        val before = snapshot()
        val answer = dryRun(packId)
        refused(answer, 503, SRT_013, before)
        answer.body() shouldContain "period calendar"
    }

    @Test
    fun `INV-AC-10766 an explicit window with the default binding is accepted`() {
        val label = label()
        val packId = candidate(label)
        val answer = dryRun(packId, window("2026-Q2", "2026-Q3", 2))
        withClue(answer.body()) { (answer.statusCode() == 200 || answer.statusCode() == 202) shouldBe true }
        windowOf(label) shouldBe Triple("2026-Q2", "2026-Q3", 2)
    }

    @Test
    fun `the stored binding is registered when no calendar bean exists and an entity with no record throws`() {
        val beans = context.getBeansOfType(LastCompletePeriodPort::class.java)
        beans.size shouldBe 1
        beans.values
            .single()
            .javaClass.simpleName shouldBe "StoredLastCompletePeriodPort"
        val tenants = context.getBean(com.dynokore.platform.tenancykernel.TenantContext::class.java)
        val thrown =
            runCatching {
                tenants.bind(
                    TENANT_A,
                    "calendar-probe",
                ) { beans.values.single().lastCompletePeriod(TENANT_A, ENTITY_E1) }
            }.exceptionOrNull()
        (thrown is PeriodCalendarUnavailableException) shouldBe true
    }
}

/** With the fixture calendar the fixture is used and the default backs off. */
@SpringBootTest(
    classes = [DryRunFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [WAIT_PROPERTY, IDEMPOTENCY, CALLER_0, SUBJECT_0, CALLER_1, SUBJECT_1],
)
class DryRunCalendarWiringTest(
    @Autowired private val context: ApplicationContext,
    @LocalServerPort port: Int,
) : DryRunSupport(port) {
    @Test
    fun `the fixture calendar is the only calendar bean and the default backs off`() {
        context
            .getBeansOfType(LastCompletePeriodPort::class.java)
            .values
            .single()
            .javaClass.simpleName shouldBe "FixtureLastCompletePeriodPort"
        context
            .getBeansOfType(HistoricalSamplePort::class.java)
            .values
            .single()
            .javaClass.simpleName shouldBe "FixtureHistoricalSamplePort"
    }
}

/** No sample adapter at all: the service starts, `dryRun` is refused naming the sample and nothing is written. */
@SpringBootTest(
    classes = [DryRunWithoutSampleAdapter::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [WAIT_PROPERTY, IDEMPOTENCY, CALLER_0, SUBJECT_0, CALLER_1, SUBJECT_1],
)
@org.springframework.test.context.ActiveProfiles("prod")
class DryRunWithoutSampleAdapterTest(
    @LocalServerPort port: Int,
    @Autowired private val context: ApplicationContext,
) : DryRunSupport(port) {
    @Test
    fun `no sample adapter leaves the context started and dryRun refused 503 SRT-013 naming the sample`() {
        context.environment.activeProfiles.contains("prod") shouldBe true
        context.getBeansOfType(HistoricalSamplePort::class.java).size shouldBe 0
        val packId = candidate()
        val before = snapshot()
        val answer = dryRun(packId, window("2026-Q3", "2026-Q3", 1))
        refused(answer, 503, SRT_013, before)
        answer.body() shouldContain "sample"
    }
}

private const val RETRY_PROPERTY = "rules.dry-run.retry-after=PT7S"
private const val SLOTS_PROPERTY = "rules.dry-run.parallelism=2"
private const val FREE_SLOT_TRIES = 60

/**
 * The saturation of the dry-run lane (INV-T-4974, option A): with every slot held the next call is refused 503
 * SRT-013 with `Retry-After`, nothing is written, and the slot comes back when a run ends. A robustness case: it
 * carries no acceptance-criterion key.
 */
@SpringBootTest(
    classes = [DryRunFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        [
            WAIT_PROPERTY, SLOTS_PROPERTY, RETRY_PROPERTY,
            IDEMPOTENCY, CALLER_0, SUBJECT_0, CALLER_1, SUBJECT_1,
        ],
)
class DryRunSaturationTest(
    @LocalServerPort port: Int,
    @Autowired private val samples: FixtureHistoricalSamplePort,
    @Autowired private val periods: FixtureLastCompletePeriodPort,
) : DryRunSupport(port) {
    @BeforeEach
    @AfterEach
    fun reset() {
        samples.clear()
        periods.reset()
    }

    @Test
    fun `saturation - all slots in use refuses the next call 503 SRT-013 with Retry-After and writes nothing`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3")))
        samples.hold = CountDownLatch(1)
        val packs = List(3) { candidate() }
        val ids =
            packs.take(2).map {
                val held = dryRun(it)
                held.statusCode() shouldBe 202
                held.headers().firstValue("Retry-After").get() shouldBe "7"
                bodyOf(held).get("dryRunId").asString()
            }
        val before = snapshot()
        val refusedCall = dryRun(packs[2])
        refused(refusedCall, 503, SRT_013, before)
        refusedCall.headers().firstValue("Retry-After").get() shouldBe "7"
        // the slot is released when a run ends: after the held runs end the next call is accepted
        samples.hold?.countDown()
        ids.forEachIndexed { i, id -> pollEnd(packs[i], id).get("state").asString() shouldBe "SUCCEEDED" }
        var accepted = 0
        repeat(FREE_SLOT_TRIES) {
            if (accepted == 0) {
                val answer = dryRun(packs[2])
                if (answer.statusCode() != 503) accepted = answer.statusCode() else Thread.sleep(POLL_MILLIS)
            }
        }
        (accepted == 200 || accepted == 202) shouldBe true
    }

    private fun bodyOf(answer: HttpResponse<String>): JsonNode = mapper.readTree(answer.body())
}

/** The three properties of the lane bind the pool, the sample wait and the header: other values, other behaviour. */
@SpringBootTest(
    classes = [DryRunFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        [
            "rules.dry-run.sample-wait=PT37S", "rules.dry-run.parallelism=1", "rules.dry-run.retry-after=PT3S",
            IDEMPOTENCY, CALLER_0, SUBJECT_0, CALLER_1, SUBJECT_1,
        ],
)
class DryRunLaneBindingTest(
    @LocalServerPort port: Int,
    @Autowired private val samples: FixtureHistoricalSamplePort,
    @Autowired private val periods: FixtureLastCompletePeriodPort,
) : DryRunSupport(port) {
    @BeforeEach
    @AfterEach
    fun reset() {
        samples.clear()
        periods.reset()
    }

    @Test
    fun `binding - the pool size, the sample wait and the Retry-After follow the properties`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3")))
        samples.hold = CountDownLatch(1)
        val first = candidate()
        val second = candidate()
        val held = dryRun(first)
        held.statusCode() shouldBe 202
        held.headers().firstValue("Retry-After").get() shouldBe "3"
        val refusedCall = dryRun(second)
        refusedCall.statusCode() shouldBe 503
        refusedCall.headers().firstValue("Retry-After").get() shouldBe "3"
        samples.waits.single() shouldBe java.time.Duration.ofSeconds(SAMPLE_WAIT_SECONDS)
    }
}

private const val SAMPLE_WAIT_SECONDS = 37L

// ---- the dry run of a staged regulatory pack (INV-T-20275; INV-AC-20274; owner ruling of 2026-11-16) ----

private const val RT_TOKEN_PREFIX = "Bearer rt:"
private const val RT_CHANGED_CODE = "ZR"
private const val RT_UNCHANGED_CODE = "SR"
private const val REQUESTED_ENTITIES =
    "select data->>'entityRef' from rules.outbox where type = '$REQUESTED' and data->>'dryRunId' = ?"
private const val TICK_TABLE = "pack_currency_state"
private const val TICK_EVENT = "com.dynokore.gst.platform.PackCurrencyChanged.v1"
private const val REQUESTS_OF_TENANT =
    "select tenant_id::text || '|' || data::text from rules.outbox where type = ? and tenant_id = ?::uuid"
private const val RUN_OF_TENANT = "select id::text from rules.dry_run_delta where tenant_id = ?::uuid"
private const val OUTBOX_OF_TENANT = "select data::text from rules.outbox where tenant_id = ?::uuid"
private const val RT_REGISTER =
    "select rules.register_platform_pack(?::uuid, ?, ?, ?, ?, ?::date, null, '1.0.0', null, ?::timestamptz)"

/** One entity's sample: two documents of period 2026-Q3, one the candidate pack changes and one it keeps. */
class RegulatoryDocs(
    val entity: String,
    val changed: Map<String, Any?>,
    val unchanged: Map<String, Any?>,
)

/**
 * The fixture sample adapter of the regulatory runs. It answers like canonical-svc: only for the entities that the
 * `PackDryRunSampleRequested` rows of the run name, so a document of an entity that was not requested never arrives.
 */
class EntityAwareSamples : HistoricalSamplePort {
    private val byEntity = ConcurrentHashMap<String, List<Map<String, Any?>>>()
    val reads = CopyOnWriteArrayList<String>()

    /** The window of the run that each read was made with. */
    val readWindows = CopyOnWriteArrayList<com.dynokore.gst.rules.application.DryRunWindow>()

    fun serve(docs: RegulatoryDocs) {
        byEntity[docs.entity] = listOf(docs.changed, docs.unchanged)
    }

    fun clear() {
        byEntity.clear()
        reads.clear()
        readWindows.clear()
    }

    override fun pages(
        dryRunId: String,
        window: com.dynokore.gst.rules.application.DryRunWindow,
        wait: java.time.Duration,
    ): List<SamplePage> {
        reads.add(dryRunId)
        readWindows.add(window)
        val requested =
            java.sql
                .DriverManager
                .getConnection(PostgresRules.pg.jdbcUrl, PostgresRules.pg.username, PostgresRules.pg.password)
                .use { c ->
                    c.prepareStatement(REQUESTED_ENTITIES).use {
                        it.setString(1, dryRunId)
                        it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    }
                }
        return listOf(SamplePage(1, true, requested.sorted().flatMap { byEntity[it].orEmpty() }))
    }

    override fun release(dryRunId: String) = Unit
}

/** The fixtures of the regulatory runs: any tenant is a principal by the token `rt:<user>:<tenant>`. */
@TestConfiguration(proxyBeanMethods = false)
@Import(MappingAllowingPorts::class, MappingDoubles::class, DryRunAllowingPermission::class)
class RegulatoryDryRunFixtures {
    @Bean fun entitySamples() = EntityAwareSamples()

    @Bean fun fixturePeriods() = FixtureLastCompletePeriodPort()

    @Bean fun fixtureEvaluation() = FixtureEvaluation()

    @Bean fun clock(): SwitchableClock = SwitchableClock()

    @Bean
    @org.springframework.context.annotation.Primary
    fun regulatoryPrincipals(
        @Qualifier("mappingEndUserPrincipalResolver") base: EndUserPrincipalResolver,
    ): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            if (authorization != null && authorization.startsWith(RT_TOKEN_PREFIX)) {
                val (user, tenant) = authorization.removePrefix(RT_TOKEN_PREFIX).split(":")
                EndUserPrincipal(user, TenantId.of(tenant), emptySet())
            } else {
                base.resolve(authorization)
            }
        }
}

/** The calls, the fixtures and the comparisons of the regulatory runs. */
open class RegulatoryDryRunSupport(
    port: Int,
    protected val replica: StructureReplica,
    protected val samples: EntityAwareSamples,
    protected val periods: FixtureLastCompletePeriodPort,
    protected val evaluation: FixtureEvaluation,
) : DryRunSupport(port) {
    protected fun newTenant(): TenantId = TenantId.of(UUID.randomUUID().toString())

    protected fun tokenOf(
        user: String,
        tenant: TenantId,
    ) = "$RT_TOKEN_PREFIX$user:$tenant"

    protected fun entity(
        tenant: TenantId,
        ref: String,
    ) = replica.accept(
        EntityProfileChanged(
            tenant,
            ref,
            StructureNode(StructureNodeKind.ENTITY, null, false, "E", ref, emptyList(), 1),
        ),
    )

    /** A changed and an unchanged document of [entity]; the candidate pack answers [RT_CHANGED_CODE] for the first. */
    protected fun docs(entity: String): RegulatoryDocs {
        val changed = UUID.randomUUID().toString()
        val unchanged = UUID.randomUUID().toString()
        evaluation.candidate[changed] = DocumentOutcome(RT_CHANGED_CODE)
        val first = doc(changed, entity, "12345.67", "1111.11")
        return RegulatoryDocs(entity, first, doc(unchanged, entity, "7654.32", "2222.22"))
    }

    private fun doc(
        uuid: String,
        entity: String,
        taxable: String,
        gst: String,
    ): Map<String, Any?> = item(uuid, "2026-Q3", taxable, gst) + ("entityRef" to entity)

    /** A platform pack row of [type]; [status] STAGED, APPROVED or ARMED. The candidate labels reach the fixture. */
    protected fun platformPack(
        type: String,
        version: String,
        status: String = "STAGED",
        effective: String = "2027-01-01",
    ): String {
        if (status != "ARMED") CANDIDATE_LABELS.add(version)
        value(
            TenantId.PLATFORM,
            RT_REGISTER,
            UUID.randomUUID().toString(),
            "$type-pack",
            type,
            version,
            if (status == "ARMED") "ARMED" else "STAGED",
            effective,
            java.time.Instant
                .now()
                .toString(),
        )
        if (status == "APPROVED") value(TenantId.PLATFORM, "select rules.approve_platform_pack(?, ?)", type, version)
        return "$type@$version"
    }

    /** A staged candidate of a fresh pack type and a pack of that type in force. */
    protected fun stagedWithCurrent(): Pair<String, String> {
        val type =
            "RT" +
                UUID
                    .randomUUID()
                    .toString()
                    .take(8)
                    .uppercase()
        platformPack(type, "cur-${UUID.randomUUID().toString().take(6)}", "ARMED", "2026-01-01")
        return type to platformPack(type, "cand-${UUID.randomUUID().toString().take(6)}")
    }

    /**
     * Every table of the schema `rules` with its rows as text, all tenants (the superuser login). The one background
     * writer of the context is the scheduled pack-currency tick (`PackCurrencyScheduling`, every 30 s): it writes
     * `pack_currency_state` and the `PackCurrencyChanged` outbox row for the staged platform packs that the tests
     * register. Neither is written by a dry-run call, so both are left out; every other table and row is compared.
     */
    protected fun everything(): Map<String, List<String>> =
        superuser { c ->
            val names =
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "select table_name from information_schema.tables " +
                                "where table_schema = 'rules' and table_type = 'BASE TABLE' order by 1",
                        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            names.filter { it != TICK_TABLE }.associateWith { name ->
                c.createStatement().use { st ->
                    val rows =
                        if (name == "outbox") {
                            "select to_jsonb(t)::text from rules.outbox t where t.type <> '$TICK_EVENT' order by 1"
                        } else {
                            "select to_jsonb(t)::text from rules.\"$name\" t order by 1"
                        }
                    st.executeQuery(rows).use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
        }

    protected fun <T> superuser(block: (java.sql.Connection) -> T): T =
        java.sql.DriverManager
            .getConnection(PostgresRules.pg.jdbcUrl, PostgresRules.pg.username, PostgresRules.pg.password)
            .use(block)

    protected fun strings(
        sql: String,
        vararg binds: String,
    ): List<String?> =
        superuser { c ->
            c.prepareStatement(sql).use { ps ->
                binds.forEachIndexed { i, b -> ps.setString(i + 1, b) }
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    /** The PackDryRunSampleRequested payloads written for [tenant], parsed. */
    protected fun requestsOf(tenant: TenantId): List<Pair<String, JsonNode>> =
        strings(REQUESTS_OF_TENANT, REQUESTED, tenant.toString())
            .map { checkNotNull(it) }
            .map { it.substringBefore('|') to mapper.readTree(it.substringAfter('|')) }

    /** The id of the only run of [tenant]. */
    protected fun runOf(tenant: TenantId): String = checkNotNull(strings(RUN_OF_TENANT, tenant.toString()).single())

    /** Starts the run under [user] and reads its status resource once it has ended. */
    protected fun report(
        ref: String,
        user: String,
        tenant: TenantId,
    ): JsonNode {
        val answer = dryRun(ref, null, user)
        withClue(answer.body()) { (answer.statusCode() == 200 || answer.statusCode() == 202) shouldBe true }
        return pollEnd(ref, runOf(tenant), user)
    }

    protected fun pollEnd(
        ref: String,
        id: String,
        user: String,
    ): JsonNode {
        repeat(POLL_LIMIT) {
            val status = json(getRun(ref, id, user))
            if (status.get("state").asString() != "RUNNING") return status
            Thread.sleep(POLL_MILLIS)
        }
        error("the dry run $id did not end")
    }

    protected fun mappingPackIdLike(
        tenant: TenantId,
        packId: String,
    ) {
        value(
            tenant,
            "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                "min_engine_version, registered_by, label, entity_ref) values (?::uuid, ?::uuid, ?, 'MAPPING', ?, " +
                "'TENANT_MAPPING', 'DRAFT', '1.0.0', 'tenancy-probe', ?, 'E-1') returning id",
            UUID.randomUUID().toString(),
            tenant.toString(),
            packId,
            "v-" + UUID.randomUUID().toString().take(6),
            "l-" + UUID.randomUUID().toString().take(6),
        )
    }

    /** One ARMED mapping pack of [tenant] in force since yesterday: what a mapping candidate is compared with. */
    protected fun armedMappingPack(tenant: TenantId) {
        val yesterday =
            java.time.LocalDate
                .now(java.time.ZoneOffset.UTC)
                .minusDays(1)
                .toString()
        value(
            tenant,
            "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                "effective_from, effective_to, min_engine_version, supersedes, registered_by, entity_ref) " +
                "values (?::uuid, ?::uuid, ?, 'MAPPING', ?, 'TENANT_MAPPING', 'ARMED', ?::date, null, '1.0.0', " +
                "null, 'tenancy-probe', 'E-1') returning id",
            UUID.randomUUID().toString(),
            tenant.toString(),
            "MAP-cur-" + UUID.randomUUID().toString().take(6),
            "cur-" + UUID.randomUUID().toString().take(6),
            yesterday,
        )
    }

    protected fun refusedAs(
        answer: HttpResponse<String>,
        status: Int,
        code: String,
        before: Map<String, List<String>>,
    ) {
        withClue(answer.body()) {
            answer.statusCode() shouldBe status
            json(answer).get("code").asString() shouldBe code
        }
        everything() shouldBe before
    }
}

/**
 * `dryRun` and `getDryRun` of a staged regulatory pack through the HTTP boundary, the real database, the fixture sample
 * adapter and the structure replica (INV-T-20275; INV-AC-20274). A regulatory `packRef` is `<packType>@<version>`.
 */
@Suppress("TooManyFunctions") // one case per refusal and per owner-ruling consequence
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(
    classes = [RegulatoryDryRunFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [WAIT_PROPERTY, IDEMPOTENCY, CALLER_0, SUBJECT_0, CALLER_1, SUBJECT_1],
)
class RegulatoryDryRunBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired replica: StructureReplica,
    @Autowired samples: EntityAwareSamples,
    @Autowired periods: FixtureLastCompletePeriodPort,
    @Autowired evaluation: FixtureEvaluation,
) : RegulatoryDryRunSupport(port, replica, samples, periods, evaluation) {
    @BeforeEach
    @AfterEach
    fun reset() {
        samples.clear()
        periods.reset()
        evaluation.reset()
    }

    private fun tenantWith(vararg entities: String): Pair<TenantId, String> {
        val tenant = newTenant()
        entities.forEach { entity(tenant, it) }
        return tenant to tokenOf("user-1", tenant)
    }

    // ---- (a) the resolution of a packRef ----

    @Test
    fun `INV-AC-20274 a packRef that names no pack answers 404 and writes no row`() {
        val (_, user) = tenantWith("E1")
        val before = everything()
        refusedAs(dryRun("NOPACK@2026.4", null, user), 404, "SRT-005", before)
        refusedAs(dryRun("UNKNOWN", null, user), 404, "SRT-005", before)
    }

    @Test
    fun `INV-AC-20274 a staged regulatory pack with no pack in force answers 409 SRT-008 and starts no run`() {
        val (_, user) = tenantWith("E1")
        val type =
            "RT" +
                UUID
                    .randomUUID()
                    .toString()
                    .take(8)
                    .uppercase()
        val ref = platformPack(type, "cand-1")
        val before = everything()
        refusedAs(dryRun(ref, null, user), 409, "SRT-008", before)
        strings("select count(*)::text from rules.dry_run_delta where pack_type = ?", type) shouldBe listOf("0")
    }

    @Test
    fun `INV-AC-20274 the id of a mapping pack of another tenant answers 404`() {
        val (_, user) = tenantWith("E1")
        val packId = seedPack("rt-${UUID.randomUUID().toString().take(6)}")
        val before = everything()
        refusedAs(dryRun(packId, null, user), 404, "SRT-005", before)
    }

    @Test
    fun `INV-AC-20274 a packRef that names a mapping pack of the tenant and a regulatory row answers 409 SRT-008`() {
        val (tenant, user) = tenantWith("E1")
        val (_, ref) = stagedWithCurrent()
        mappingPackIdLike(tenant, ref)
        armedMappingPack(tenant) // so that the mapping reading of the ref alone would be accepted
        val before = everything()
        refusedAs(dryRun(ref, null, user), 409, "SRT-008", before)
    }

    @Test
    fun `INV-AC-20274 a regulatory row that is APPROVED answers 409 SRT-008 and starts no run`() {
        val (_, user) = tenantWith("E1")
        val type =
            "RT" +
                UUID
                    .randomUUID()
                    .toString()
                    .take(8)
                    .uppercase()
        platformPack(type, "cur-1", "ARMED", "2026-01-01")
        val ref = platformPack(type, "cand-1", "APPROVED")
        val before = everything()
        refusedAs(dryRun(ref, null, user), 409, "SRT-008", before)
    }

    @Test
    fun `INV-AC-20274 look-alike refs answer 404 and write nothing`() {
        val (_, user) = tenantWith("E1")
        val type =
            "RT" +
                UUID
                    .randomUUID()
                    .toString()
                    .take(8)
                    .uppercase()
        platformPack(type, "cur-1", "ARMED", "2026-01-01")
        platformPack(type, "2026.4")
        val before = everything()
        listOf(type, "$type@", "@2026.4", "${type.lowercase()}@2026.4", "$type@2026.4@x", "$type@2026.4%20").forEach {
            refusedAs(dryRun(it, null, user), 404, "SRT-005", before)
        }
    }

    @Test
    fun `INV-AC-20274 getDryRun of a run of T1 under the ref of another version answers 404`() {
        val (tenant, user) = tenantWith("E1")
        samples.serve(docs("E1"))
        val (type, ref) = stagedWithCurrent()
        report(ref, user, tenant).get("state").asString() shouldBe "SUCCEEDED"
        val other = platformPack(type, "other-1")
        getRun(other, runOf(tenant), user).statusCode() shouldBe 404
        getRun("$type@x", runOf(tenant), user).statusCode() shouldBe 404
        getRun(ref, runOf(tenant), user).statusCode() shouldBe 200
    }

    // ---- (b) the sample and the report of the starting tenant (owner ruling of 2026-11-16) ----

    @Test
    fun `INV-AC-20274 a run of T1 samples the entities of T1 only`() {
        val (t1, user1) = tenantWith("E1", "E2")
        val (_, _) = tenantWith("E3")
        listOf("E1", "E2", "E3").forEach { samples.serve(docs(it)) }
        val (_, ref) = stagedWithCurrent()
        report(ref, user1, t1).get("state").asString() shouldBe "SUCCEEDED"
        val rows = requestsOf(t1)
        rows.map { it.second.get("entityRef").asString() }.toSet() shouldBe setOf("E1", "E2")
        rows.size shouldBe 2
        rows.all { it.first == t1.toString() } shouldBe true
        rows.none { it.second.get("entityRef").asString() == "E3" } shouldBe true
        rows.all { it.second.get("firstPeriodRef").asString() == "2026-Q3" } shouldBe true
    }

    @Test
    fun `INV-AC-20274 each sample request carries the window of its own entity`() {
        val (t1, user1) = tenantWith("E1", "E2")
        listOf("E1", "E2").forEach { samples.serve(docs(it)) }
        periods.byEntity["E1"] = "2026-Q2"
        periods.byEntity["E2"] = "2026-Q3"
        val (_, ref) = stagedWithCurrent()
        report(ref, user1, t1).get("state").asString() shouldBe "SUCCEEDED"
        val byEntity = requestsOf(t1).map { it.second }.associateBy { it.get("entityRef").asString() }
        byEntity.keys shouldBe setOf("E1", "E2")
        val expected = mapOf("E1" to "2026-Q2", "E2" to "2026-Q3")
        expected.forEach { (entity, period) ->
            val row = checkNotNull(byEntity[entity])
            row.get("firstPeriodRef").asString() shouldBe period
            row.get("lastPeriodRef").asString() shouldBe period
            row.get("periodCount").asInt() shouldBe 1
        }
        // the run row carries the window of the first entity by sorted entityRef
        val runWindow = samples.readWindows.single()
        runWindow.firstPeriodRef shouldBe "2026-Q2"
        runWindow.lastPeriodRef shouldBe "2026-Q2"
    }

    @Test
    fun `INV-AC-20274 the report counts only the documents of the starting tenant`() {
        val (t1, user1) = tenantWith("E1", "E2")
        val (t2, user2) = tenantWith("E3")
        listOf("E1", "E2", "E3").forEach { samples.serve(docs(it)) }
        val (_, ref) = stagedWithCurrent()
        report(ref, user1, t1).get("result").get("changedCount").asInt() shouldBe 2
        report(ref, user2, t2).get("result").get("changedCount").asInt() shouldBe 1
    }

    @Test
    fun `INV-AC-20274 another tenant cannot read the report`() {
        val (t1, user1) = tenantWith("E1")
        val (_, user2) = tenantWith("E3")
        samples.serve(docs("E1"))
        val (_, ref) = stagedWithCurrent()
        report(ref, user1, t1)
        val id = runOf(t1)
        val before = everything()
        refusedAs(getRun(ref, id, user2), 404, "SRT-005", before)
        refusedAs(getRun(ref, UUID.randomUUID().toString(), user2), 404, "SRT-005", before)
    }

    @Test
    fun `INV-AC-20274 another principal of the same tenant is refused as on the mapping path`() {
        val (t1, user1) = tenantWith("E1")
        samples.serve(docs("E1"))
        val (_, ref) = stagedWithCurrent()
        report(ref, user1, t1)
        val colleague = tokenOf("user-2", t1)
        getRun(ref, runOf(t1), colleague).statusCode() shouldBe 404
        getRun(ref, runOf(t1), user1).statusCode() shouldBe 200
    }

    @Test
    fun `INV-AC-20274 a run of a tenant with no entity is not a complete report`() {
        val (t1, user1) = tenantWith()
        val (_, ref) = stagedWithCurrent()
        val answer = dryRun(ref, null, user1)
        withClue(answer.body()) { (answer.statusCode() == 200 || answer.statusCode() == 202) shouldBe true }
        val status = pollEnd(ref, runOf(t1), user1)
        status.get("state").asString() shouldBe "FAILED"
        (status.has("result") && !status.get("result").isNull) shouldBe false
        strings("select state from rules.dry_run_delta where tenant_id = ?::uuid", t1.toString()) shouldBe
            listOf("SAMPLE_UNAVAILABLE")
        requestsOf(t1).size shouldBe 0
        samples.reads.size shouldBe 0
    }

    @Test
    fun `INV-AC-20274 an entity without a period calendar refuses the call`() {
        val (_, user1) = tenantWith("E1", "E2")
        val (_, ref) = stagedWithCurrent()
        val before = everything()
        periods.unavailable = true
        refusedAs(dryRun(ref, null, user1), 503, SRT_013, before)
        periods.unavailable = false
        periods.answer = null
        refusedAs(dryRun(ref, null, user1), 503, SRT_013, before)
    }

    @Test
    fun `INV-AC-20274 no document value reaches a log record or an outbox row`(output: CapturedOutput) {
        val (t1, user1) = tenantWith("E1", "E2")
        val fixtures = listOf("E1", "E2").map { docs(it).also(samples::serve) }
        val (_, ref) = stagedWithCurrent()
        report(ref, user1, t1).get("state").asString() shouldBe "SUCCEEDED"
        val values =
            fixtures.flatMap { listOf(it.changed, it.unchanged) }.flatMap {
                listOf(it.getValue("docUuid"), it.getValue("taxableValue"), it.getValue("gstValue")).map(Any?::toString)
            }
        val outbox = strings(OUTBOX_OF_TENANT, t1.toString()).joinToString("\n")
        val logs = output.all
        values.forEach {
            withClue("outbox holds $it") { outbox.contains(it) shouldBe false }
            withClue("log holds $it") { logs.contains(it) shouldBe false }
        }
        listOf(RT_CHANGED_CODE, RT_UNCHANGED_CODE).forEach {
            val word = Regex("(?<![A-Za-z0-9_])$it(?![A-Za-z0-9_])")
            withClue("outbox holds the code $it") { word.containsMatchIn(outbox) shouldBe false }
            withClue("log holds the code $it") { word.containsMatchIn(logs) shouldBe false }
        }
    }
}
