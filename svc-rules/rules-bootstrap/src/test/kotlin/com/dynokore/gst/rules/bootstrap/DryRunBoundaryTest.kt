package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.PeriodCalendarUnavailableException
import com.dynokore.gst.rules.application.SamplePage
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
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
@Import(MappingAllowingPorts::class, MappingDoubles::class)
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
@Import(MappingAllowingPorts::class, MappingDoubles::class)
class DryRunFixturesWithoutCalendar {
    @Bean fun fixtureSamples() = FixtureHistoricalSamplePort()

    @Bean fun fixtureEvaluation() = FixtureEvaluation()
}

/** Removes the event adapter of the sample (slice 3) from the context: the production case before canonical-svc. */
@TestConfiguration(proxyBeanMethods = false)
@Import(MappingAllowingPorts::class, MappingDoubles::class)
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

    @Test
    fun `a changed document with no supply type makes getDryRun answer a server error with no result`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3") - "supplyType"))
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        val packId = candidate()
        val run = dryRun(packId)
        val id = if (run.statusCode() == 202) json(run).get("dryRunId").asString() else lastRunId(packId)
        val read = pollRaw(packId, id)
        withClue("${read.statusCode()} ${read.body()}") { (read.statusCode() in 500..599) shouldBe true }
        json(read).has("result") shouldBe false
    }

    @Test
    fun `a changed document with the supply type STANDARD_RATED answers 200 with totals adding up to changedCount`() {
        samples.serve("2026-Q3", listOf(item(D1, "2026-Q3")))
        evaluation.candidate[D1] = DocumentOutcome("ZR")
        val result = finished(candidate()).get("result")
        result.get("changedCount").asInt() shouldBe 1
        elements(result.get("supplyTypeTotals")).sumOf { it.get("documentCount").asInt() } shouldBe 1
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
    fun `the default binding is registered when no calendar bean exists and the one bean throws`() {
        val beans = context.getBeansOfType(LastCompletePeriodPort::class.java)
        beans.size shouldBe 1
        val thrown = runCatching { beans.values.single().lastCompletePeriod(TENANT_A, ENTITY_E1) }.exceptionOrNull()
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
