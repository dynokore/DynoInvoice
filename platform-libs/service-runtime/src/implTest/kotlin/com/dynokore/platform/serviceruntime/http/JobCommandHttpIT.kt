package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.serviceruntime.contract.ContractValidator
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.FakeAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandOutcome
import com.dynokore.platform.serviceruntime.jobs.JobCommandRequest
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobEntitySource
import com.dynokore.platform.serviceruntime.jobs.JobPauseState
import com.dynokore.platform.serviceruntime.jobs.JobRunState
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.serviceruntime.jobs.JobsHarness
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E1
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E2
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SCHEMA
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.START
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_A
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import com.dynokore.platform.serviceruntime.jobs.MovableDeadlines
import com.dynokore.platform.serviceruntime.jobs.MutableClock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * runJobNow, pauseJob and resumeJob driven through HTTP onto INV-T-2874's JobRuntime over PostgreSQL 17 and 18
 * (INV-API-3023, INV-API-3025, INV-Q-3535): paths, Idempotency-Key (API-15), JobRunState 202/200, JobPauseState
 * 200, the JOB catalogue codes at their statuses (API-21) and the closed code-less cases (ARC-19), the actor from
 * the propagated token (CS-23, API-24). Every request sent and every 2xx or catalogue-coded response is validated
 * against the pinned platform-contracts `service-runtime/openapi.yaml`. The closed code-less cases are checked
 * for their ARC-19 shape only: the pinned error envelope requires `code`, which ARC-19 and INV-API-3023 leave out
 * for them (reported on INV-T-2875).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobCommandHttpIT {
    private val harnesses = ConcurrentHashMap<String, JobsHarness>()
    private val mapper = ObjectMapper()
    private val contracts = ContractValidator()
    private val openapi = "service-runtime/openapi.yaml"

    private fun harness(image: String) = harnesses.computeIfAbsent(image) {
        JobsHarness(it).also { h -> h.fixture.run(IdempotencySchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.WORKER)) }
    }

    @AfterAll
    fun close() = harnesses.values.forEach { it.close() }

    private val operator = "platform-op"
    private val engineer = "eng-e1"
    private val authorizer = FakeAuthorizer(mapOf(engineer to setOf(E1)), setOf(operator))

    /** Tokens of the test: the engineer's covers E-001 and E-002 but it holds a grant on E-001 only. */
    private val principals =
        EndUserPrincipalResolver { header ->
            when (header) {
                "Bearer eng" -> EndUserPrincipal(engineer, TENANT_A, setOf(E1, E2))
                "Bearer op" -> EndUserPrincipal(operator, null, emptySet())
                else -> null
            }
        }

    private inner class Service(
        image: String,
        commands: ((JobCommands) -> JobCommands)? = null,
    ) {
        val h = harness(image)
        val clock = MutableClock(START)
        val deadlines = MovableDeadlines()
        val replica = h.replica("svc-1", clock, authorizer, deadlines)
        val idempotency = IdempotencyStore(replica.scope, h.fixture.appDataSource(), h.fixture.workerDataSource(), SCHEMA, clock)
        val servlet = JobCommandServlet(commands?.invoke(replica.runtime) ?: replica.runtime, principals, replica.tenants, idempotency)

        fun post(
            path: String,
            body: String?,
            key: String? = UUID.randomUUID().toString(),
            token: String? = "Bearer eng",
        ): Answer {
            val request = MockHttpServletRequest("POST", path)
            token?.let { request.addHeader("Authorization", it) }
            key?.let { request.addHeader("Idempotency-Key", it) }
            body?.let {
                request.contentType = "application/json"
                request.setContent(it.toByteArray())
            }
            val response = MockHttpServletResponse()
            servlet.service(request, response)
            return Answer(path, response.status, response.contentType.orEmpty(), mapper.readTree(response.contentAsString), body, key)
        }
    }

    private inner class Answer(
        val path: String,
        val status: Int,
        val contentType: String,
        val body: JsonNode,
        private val requestBody: String?,
        private val key: String?,
    ) {
        private val operation get() = contracts.operation(openapi, "POST", path)

        /** The request was valid and the response matches the contract. */
        fun conforms(): Answer {
            val headers = buildMap { key?.let { put("idempotency-key", it) } }
            operation.requestErrors(headers, mapper.readTree(requestBody ?: "{}")).shouldBeEmpty()
            operation.responseErrors(status, contentType, body).shouldBeEmpty()
            return this
        }

        /** A closed service-runtime case (ARC-19): the status, code-less, type about:blank, a correlation id. */
        fun closed(expected: Int): Answer {
            status shouldBe expected
            contentType.substringBefore(';') shouldBe Problems.CONTENT_TYPE
            body.has("code") shouldBe false
            body.get("type").asString() shouldBe "about:blank"
            body.get("status").asInt() shouldBe expected
            body.has("correlationId") shouldBe true
            return this
        }

        fun coded(
            code: String,
            expected: Int,
        ): Answer {
            status shouldBe expected
            body.get("code").asString() shouldBe code
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe "TERMINAL"
            return conforms()
        }
    }

    private val e1 = JobTarget.Entity(TENANT_A, E1)
    private val e2 = JobTarget.Entity(TENANT_A, E2)
    private val entities = JobEntitySource { listOf(e1, e2) }

    private fun runPath(job: String) = "/internal/v1/jobs/$job/run"

    private fun pausePath(job: String) = "/internal/v1/jobs/$job/pause"

    private fun resumePath(job: String) = "/internal/v1/jobs/$job/resume"

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `runJobNow starts a run with 202, a repeat in the window is 200, and a same-key replay returns the stored response`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-run"), entities) { }
        val key = UUID.randomUUID().toString()
        val first = s.post(runPath("http-run"), """{"entityRef":"E-001","reason":"catch up"}""", key).conforms()
        first.status shouldBe 202
        first.body.get("trigger").asString() shouldBe "MANUAL"
        first.body.get("entityRef").asString() shouldBe "E-001"
        first.body.get("repeat").asBoolean() shouldBe false

        val replay = s.post(runPath("http-run"), """{"entityRef":"E-001","reason":"catch up"}""", key).conforms()
        replay.status shouldBe 202
        replay.body shouldBe first.body

        val repeat = s.post(runPath("http-run"), """{"entityRef":"E-001"}""").conforms()
        repeat.status shouldBe 200
        repeat.body.get("repeat").asBoolean() shouldBe true
        s.h.runs("http-run").filter { it["trigger"] == "MANUAL" } shouldHaveSize 1
        s.h.events(JobsHarness.AUDIT, "submission-svc/http-run") shouldHaveSize 1
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the Idempotency-Key is required, and reusing it with a different body is 422`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-key"), entities) { }
        s.post(runPath("http-key"), """{"entityRef":"E-001"}""", key = null).closed(400)
        s.post(runPath("http-key"), """{"entityRef":"E-001"}""", key = "not-a-uuid").closed(400)
        val key = UUID.randomUUID().toString()
        s.post(runPath("http-key"), """{"entityRef":"E-001"}""", "\"$key\"").closed(400)
        s.post(runPath("http-key"), """{"entityRef":"E-001"}""", key).conforms().status shouldBe 202
        s.post(runPath("http-key"), """{"entityRef":"E-001","reason":"other"}""", key).closed(422)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a replay while the original is in flight is 409`(image: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s =
            Service(image) { inner ->
                object : JobCommands by inner {
                    override fun runNow(request: JobCommandRequest): JobCommandOutcome<JobRunState> {
                        entered.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        return inner.runNow(request)
                    }
                }
            }
        s.replica.runtime.register(job("http-flight"), entities) { }
        val key = UUID.randomUUID().toString()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Answer> { s.post(runPath("http-flight"), """{"entityRef":"E-001"}""", key) }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            s.post(runPath("http-flight"), """{"entityRef":"E-001"}""", key).closed(409)
            release.countDown()
            first.get(10, TimeUnit.SECONDS).status shouldBe 202
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the idempotency record commits with the command, and a rollback leaves neither`(image: String) {
        var crash = true
        val s =
            Service(image) { inner ->
                object : JobCommands by inner {
                    override fun runNow(request: JobCommandRequest): JobCommandOutcome<JobRunState> {
                        if (!crash) return inner.runNow(request)
                        // the command fails after its record is written, before its transaction commits
                        val failing =
                            request.copy(
                                journal = { done ->
                                    request.journal?.withinTransaction(done)
                                    error("crash before commit")
                                },
                            )
                        return inner.runNow(failing)
                    }
                }
            }
        s.replica.runtime.register(job("http-tx"), entities) { }
        val key = UUID.randomUUID().toString()
        fun records() = s.h.rows("select status from $SCHEMA.idempotency_key where idempotency_key = ?::uuid", key)

        s.post(runPath("http-tx"), """{"entityRef":"E-001"}""", key).closed(500)
        records() shouldHaveSize 0
        s.h.runs("http-tx").filter { it["trigger"] == "MANUAL" } shouldHaveSize 0
        s.h.events(JobsHarness.AUDIT, "submission-svc/http-tx") shouldHaveSize 0

        crash = false
        s.post(runPath("http-tx"), """{"entityRef":"E-001"}""", key).conforms().status shouldBe 202
        records().map { it["status"] } shouldBe listOf(202)
        s.h.runs("http-tx").filter { it["trigger"] == "MANUAL" } shouldHaveSize 1
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `refusals map to their catalogue code and status, the closed cases stay code-less`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-refuse"), entities) { }
        s.replica.runtime.register(job("http-platform", scope = JobEntityScope.PLATFORM), body = { })
        s.post(runPath("no-such-job"), """{"entityRef":"E-001"}""").coded("JOB-001", 404)
        // a whole-job run by a tenant role, and an entity in the token without a grant
        s.post(runPath("http-refuse"), "{}").coded("JOB-002", 403)
        s.post(runPath("http-refuse"), """{"entityRef":"E-002"}""").coded("JOB-002", 403)
        // an entityRef outside the token's entity set (API-24), whether or not it exists
        s.post(runPath("http-refuse"), """{"entityRef":"E-404"}""").closed(403)
        s.post(runPath("http-refuse"), """{"entityRef":"E-001"}""", token = null).closed(401)
        s.post(runPath("http-refuse"), """{"entityRef":"E-001"}""", token = "Bearer forged").closed(401)
        // the platform operator naming an entity of a PLATFORM-scoped job (INV-Q-3537)
        s.post(runPath("http-platform"), """{"entityRef":"E-001"}""", token = "Bearer op").closed(403)
        s.post(runPath("http-platform"), "{}", token = "Bearer op").conforms().status shouldBe 202
        s.post(runPath("http-refuse"), """{"entityRef":42}""").closed(400)
        s.post(runPath("http-refuse"), "not json").closed(400)
        s.post("/internal/v1/jobs/http-refuse/stop", "{}").closed(404)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `pauseJob and resumeJob answer the key's own JobPauseState`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-pause", criticality = JobCriticality.DEADLINE_CRITICAL), entities) { }
        s.deadlines.due[e1] = START.plus(Duration.ofDays(2))
        val until = START.plus(Duration.ofHours(5))
        s.post(pausePath("http-pause"), """{"entityRef":"E-001"}""").closed(400)
        s.post(pausePath("http-pause"), """{"entityRef":"E-001","until":"yesterday"}""").closed(400)
        s.post(pausePath("http-pause"), """{"entityRef":"E-001","until":"${START.minusSeconds(1)}"}""").coded("JOB-004", 422)
        s.post(pausePath("http-pause"), """{"entityRef":"E-001","until":"${START.plus(Duration.ofDays(3))}"}""").coded("JOB-003", 422)

        val paused = s.post(pausePath("http-pause"), """{"entityRef":"E-001","until":"$until"}""").conforms()
        paused.status shouldBe 200
        paused.body.get("state").asString() shouldBe "PAUSED"
        Instant.parse(paused.body.get("until").asString()) shouldBe until
        paused.body.has("previousUntil") shouldBe false

        val later = until.plus(Duration.ofHours(1))
        val replaced = s.post(pausePath("http-pause"), """{"entityRef":"E-001","until":"$later"}""").conforms()
        Instant.parse(replaced.body.get("previousUntil").asString()) shouldBe until
        replaced.body.get("pausedAt") shouldBe paused.body.get("pausedAt")

        s.post(resumePath("http-pause"), """{"entityRef":"E-001"}""").conforms().body.get("state").asString() shouldBe "ACTIVE"
        val again = s.post(resumePath("http-pause"), """{"entityRef":"E-001"}""").conforms()
        again.status shouldBe 200
        again.body.get("state").asString() shouldBe "ACTIVE"
        s.post(pausePath("http-pause"), """{"until":"$until"}""").coded("JOB-002", 403)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `the contract's x-operation reversibility is the one service-runtime declares per job command`(image: String) {
        JobCommand.entries.forEach { command ->
            val path = when (command) {
                JobCommand.RUN_NOW -> runPath("j")
                JobCommand.PAUSE -> pausePath("j")
                JobCommand.RESUME -> resumePath("j")
            }
            val operation = contracts.operation(openapi, "POST", path)
            operation.operationId shouldBe command.operationId
            operation.reversibility shouldBe command.reversibility.name
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `keys older than the retention are purged across tenants`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-purge"), entities) { }
        s.post(runPath("http-purge"), """{"entityRef":"E-001"}""").status shouldBe 202
        s.clock.advance(Duration.ofHours(25))
        (s.idempotency.purge() >= 1) shouldBe true
    }
}
