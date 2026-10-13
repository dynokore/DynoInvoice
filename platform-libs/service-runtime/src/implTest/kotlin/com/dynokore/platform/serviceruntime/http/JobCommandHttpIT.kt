package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.contract.ContractValidator
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.FakeAuthorizer
import com.dynokore.platform.serviceruntime.jobs.FireResult
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandOutcome
import com.dynokore.platform.serviceruntime.jobs.JobCommandRequest
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobEntitySource
import com.dynokore.platform.serviceruntime.jobs.JobPauseState
import com.dynokore.platform.serviceruntime.jobs.JobRunState
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.serviceruntime.jobs.JobsHarness
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E1
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E2
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SCHEMA
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SERVICE
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.START
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_A
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import com.dynokore.platform.serviceruntime.jobs.MovableDeadlines
import com.dynokore.platform.serviceruntime.jobs.MutableClock
import com.dynokore.platform.serviceruntime.jobs.TransactionalJobCommandJournal
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * runJobNow, pauseJob and resumeJob driven through HTTP onto INV-T-2874's JobRuntime over PostgreSQL 17 and 18
 * (INV-API-3023, INV-API-3025, INV-Q-3535): paths, Idempotency-Key (API-15), JobRunState 202/200, JobPauseState
 * 200, the JOB catalogue codes at their statuses (API-21) and the closed cases (ARC-19) at their SRT codes (owner
 * ruling INV-Q-3720), the actor from the propagated token (CS-23, API-24). Every request sent and every response
 * is validated against the pinned platform-contracts `service-runtime/openapi.yaml`, and every error response
 * against the pinned error envelope, which requires `code`: no response is code-less (INV-REQ-827).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobCommandHttpIT {
    private val harnesses = ConcurrentHashMap<String, JobsHarness>()
    private val mapper = ObjectMapper()
    private val contracts = ContractValidator()
    private val openapi = "service-runtime/openapi.yaml"
    private val envelope = "service-runtime/schemas/error-envelope.schema.json"
    private val keyInvalid = ClosedErrorCode.IDEMPOTENCY_KEY_INVALID

    private fun harness(image: String) =
        harnesses.computeIfAbsent(image) {
            JobsHarness(it).also { h ->
                h.fixture.run(IdempotencySchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS))
            }
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

                // the authority replica is catching up: the entity set is not current (INV-Q-3771)
                "Bearer eng-catchup" -> EndUserPrincipal(engineer, TENANT_A, emptySet(), entitySetCurrent = false)

                else -> null
            }
        }

    private inner class Service(
        image: String,
        authority: AuthorityReplica? = null,
        app: DataSource? = null,
        commands: ((JobCommands) -> JobCommands)? = null,
    ) {
        val h = harness(image)
        val clock = MutableClock(START)
        val deadlines = MovableDeadlines()
        val replica = h.replica("svc-1", clock, authorizer, deadlines, app = app ?: h.fixture.appDataSource())
        val idempotency = IdempotencyStore(replica.scope, SCHEMA)
        val servlet =
            JobCommandServlet(
                commands?.invoke(replica.runtime) ?: replica.runtime,
                principals,
                replica.scope,
                idempotency,
                authority,
                clock,
            )

        fun post(
            path: String,
            body: String?,
            key: String? = UUID.randomUUID().toString(),
            token: String? = "Bearer eng",
            method: String = "POST",
        ): Answer {
            val request = MockHttpServletRequest(method, path)
            token?.let { request.addHeader("Authorization", it) }
            key?.let { request.addHeader("Idempotency-Key", it) }
            body?.let {
                request.contentType = "application/json"
                request.setContent(it.toByteArray())
            }
            val response = MockHttpServletResponse()
            servlet.service(request, response)
            return Answer(
                path,
                response.status,
                response.contentType.orEmpty(),
                mapper.readTree(response.contentAsString),
                body,
                key,
            )
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

        /** A closed service-runtime case (ARC-19) at its SRT code and status (INV-Q-3720), never code-less. */
        fun closed(expected: ClosedErrorCode): Answer {
            status shouldBe expected.httpStatus
            contentType.substringBefore(';') shouldBe Problems.CONTENT_TYPE
            body.get("code").asString() shouldBe expected.code
            body.get("type").asString() shouldBe "https://errors.invoice.internal/${expected.code}"
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe expected.disposition
            body.get("remediationOwner").asString() shouldBe "Platform"
            body.get("status").asInt() shouldBe expected.httpStatus
            if (expected == ClosedErrorCode.INTERNAL_ERROR) body.has("detail") shouldBe false
            contracts.schemaErrors(envelope, body).shouldBeEmpty()
            return this
        }

        fun coded(
            code: String,
            expected: Int,
        ): Answer {
            status shouldBe expected
            body.get("code").asString() shouldBe code
            body.get("type").asString() shouldBe "https://errors.invoice.internal/$code"
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe "TERMINAL"
            contracts.schemaErrors(envelope, body).shouldBeEmpty()
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
    fun `runJobNow starts a run with 202, a repeat in the window is 200, a same-key replay returns the stored response`(
        image: String,
    ) {
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
        s.post(runPath("http-key"), """{"entityRef":"E-001"}""", key = null).closed(keyInvalid)
        s.post(runPath("http-key"), """{"entityRef":"E-001"}""", key = "not-a-uuid").closed(keyInvalid)
        val key = UUID.randomUUID().toString()
        s.post(runPath("http-key"), """{"entityRef":"E-001"}""", "\"$key\"").closed(keyInvalid)
        s.post(runPath("http-key"), """{"entityRef":"E-001"}""", key).conforms().status shouldBe 202
        s
            .post(
                runPath("http-key"),
                """{"entityRef":"E-001","reason":"other"}""",
                key,
            ).closed(ClosedErrorCode.DIFFERENT_REQUEST)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `two concurrent requests with one key give one effect and one 409 at once`(image: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s =
            Service(image) { inner ->
                object : JobCommands by inner {
                    override fun runNow(request: JobCommandRequest): JobCommandOutcome<JobRunState> {
                        val journal = request.journal as TransactionalJobCommandJournal
                        // the original holds its guard in its command transaction, its effect done, not yet committed
                        val holding =
                            object : TransactionalJobCommandJournal {
                                override fun begin() = journal.begin()

                                override fun withinTransaction(outcome: JobCommandOutcome.Done<*>) {
                                    entered.countDown()
                                    release.await(10, TimeUnit.SECONDS)
                                    journal.withinTransaction(outcome)
                                }
                            }
                        return inner.runNow(request.copy(journal = holding))
                    }
                }
            }
        s.replica.runtime.register(job("http-flight"), entities) { }
        val key = UUID.randomUUID().toString()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Answer> { s.post(runPath("http-flight"), """{"entityRef":"E-001"}""", key) }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            // answered while the original still holds its guard: no wait for the lock
            s.post(runPath("http-flight"), """{"entityRef":"E-001"}""", key).closed(ClosedErrorCode.IN_FLIGHT)
            release.countDown()
            first.get(10, TimeUnit.SECONDS).status shouldBe 202
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        s.h.runs("http-flight").filter { it["trigger"] == "MANUAL" } shouldHaveSize 1
        s.h.events(JobsHarness.AUDIT, "submission-svc/http-flight") shouldHaveSize 1
        s.h.rows("select status from $SCHEMA.idempotency_key where idempotency_key = ?::uuid", key).map {
            it["status"]
        } shouldBe
            listOf(202)
    }

    /**
     * TPL-04's call shape, `tx.inTransaction { idempotency.once { ... } }`: the guard and the lookup are the first
     * step of the open transaction, before the block, so a sequential or concurrent repeat of the key never runs
     * the effect again.
     */
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `inside an open transaction a repeat of the key runs no second effect`(image: String) {
        val s = Service(image)
        val key = UUID.randomUUID()
        val effects = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun command(hold: Boolean): IdempotencyOutcome =
            s.replica.tenants.bind(TENANT_A, "tpl04-actor") {
                s.replica.tx.inTransaction {
                    s.idempotency.once(TENANT_A, "tpl04-actor", key, "fp") {
                        effects.incrementAndGet()
                        if (hold) {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                        StoredResponse(201, "{}")
                    }
                }
            }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<IdempotencyOutcome> { command(hold = true) }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            // concurrent: answered while the original holds its guard, without running the block
            command(hold = false) shouldBe IdempotencyOutcome.InFlight
            release.countDown()
            first.get(10, TimeUnit.SECONDS) shouldBe IdempotencyOutcome.Fresh(StoredResponse(201, "{}"))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        // sequential: the stored response, the block not run
        command(hold = false) shouldBe IdempotencyOutcome.Replay(StoredResponse(201, "{}"))
        effects.get() shouldBe 1
    }

    /**
     * INV-T-3947 (coordinator ruling INV-Q-3945): an idempotent command holds at most one app-pool connection, so
     * M and 2M concurrent commands on an app pool of maximum M, started together, all finish with their 2xx and
     * no connection timeout. Before the fix each command held a guard connection while it waited for a second.
     */
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `M and 2M concurrent commands on an app pool of maximum M all finish`(image: String) {
        val h = harness(image)
        val pool =
            HikariDataSource(
                HikariConfig().apply {
                    dataSource = h.fixture.roleDataSource(OutboxPostgresFixture.APP)
                    maximumPoolSize = POOL_MAX
                    connectionTimeout = Duration.ofSeconds(2).toMillis()
                },
            )
        try {
            for (commands in listOf(POOL_MAX, 2 * POOL_MAX)) {
                val s = Service(image, app = TenantAwareDataSource(pool))
                val jobs = (1..commands).map { "http-pool-$commands-$it" }
                jobs.forEach { s.replica.runtime.register(job(it), entities) { } }
                val start = CyclicBarrier(commands)
                val threads = Executors.newFixedThreadPool(commands)
                try {
                    val answers =
                        jobs.map { name ->
                            threads.submit<Int> {
                                start.await(10, TimeUnit.SECONDS)
                                s.post(runPath(name), """{"entityRef":"E-001"}""").status
                            }
                        }
                    answers.map { it.get(60, TimeUnit.SECONDS) } shouldBe List(commands) { 202 }
                } finally {
                    threads.shutdownNow()
                }
                jobs.forEach { s.h.runs(it).filter { run -> run["trigger"] == "MANUAL" } shouldHaveSize 1 }
            }
        } finally {
            pool.close()
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a no-state refusal is recorded in its own one transaction and replayed`(image: String) {
        val s = Service(image)
        s.h.fixture.captureActors("$SCHEMA.idempotency_key")
        val key = UUID.randomUUID().toString()
        s.post(runPath("http-late"), """{"entityRef":"E-001"}""", key).coded("JOB-001", 404)
        s.h.rows("select status from $SCHEMA.idempotency_key where idempotency_key = ?::uuid", key).map {
            it["status"]
        } shouldBe
            listOf(404)
        // recorded in a transaction bound to the command's principal, never the service (INV-Q-4268)
        s.h.fixture.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer)
        // the job exists now, but the key's stored refusal is replayed: nothing runs
        s.replica.runtime.register(job("http-late"), entities) { }
        s.post(runPath("http-late"), """{"entityRef":"E-001"}""", key).coded("JOB-001", 404)
        s.h.runs("http-late") shouldHaveSize 0
        s
            .post(
                runPath("http-late"),
                """{"entityRef":"E-001","reason":"other"}""",
                key,
            ).closed(ClosedErrorCode.DIFFERENT_REQUEST)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
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

        s.post(runPath("http-tx"), """{"entityRef":"E-001"}""", key).closed(ClosedErrorCode.INTERNAL_ERROR)
        records() shouldHaveSize 0
        s.h.runs("http-tx").filter { it["trigger"] == "MANUAL" } shouldHaveSize 0
        s.h.events(JobsHarness.AUDIT, "submission-svc/http-tx") shouldHaveSize 0

        crash = false
        s.h.fixture.captureActors("$SCHEMA.idempotency_key")
        s.post(runPath("http-tx"), """{"entityRef":"E-001"}""", key).conforms().status shouldBe 202
        records().map { it["status"] } shouldBe listOf(202)
        // recorded in the command's own transaction, bound to the operator's principal (INV-Q-4268)
        s.h.fixture.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer)
        s.h.runs("http-tx").filter { it["trigger"] == "MANUAL" } shouldHaveSize 1
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `refusals map to their catalogue code and status, the closed cases to their SRT codes`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-refuse"), entities) { }
        s.replica.runtime.register(job("http-platform", scope = JobEntityScope.PLATFORM), body = { })
        s.post(runPath("no-such-job"), """{"entityRef":"E-001"}""").coded("JOB-001", 404)
        // a whole-job run by a tenant role, and an entity in the token without a grant
        s.post(runPath("http-refuse"), "{}").coded("JOB-002", 403)
        s.post(runPath("http-refuse"), """{"entityRef":"E-002"}""").coded("JOB-002", 403)
        // an entityRef outside the principal's entity set (API-24), whether or not it exists
        s.post(runPath("http-refuse"), """{"entityRef":"E-404"}""").closed(ClosedErrorCode.ACCESS_DENIED)
        s
            .post(
                runPath("http-refuse"),
                """{"entityRef":"E-001"}""",
                token = null,
            ).closed(ClosedErrorCode.UNAUTHENTICATED)
        s
            .post(
                runPath("http-refuse"),
                """{"entityRef":"E-001"}""",
                token = "Bearer forged",
            ).closed(ClosedErrorCode.UNAUTHENTICATED)
        // the platform operator naming an entity of a PLATFORM-scoped job: 400, no per-entity share (INV-Q-3537)
        s
            .post(
                runPath("http-platform"),
                """{"entityRef":"E-001"}""",
                token = "Bearer op",
            ).closed(ClosedErrorCode.CONTRACT_VIOLATION)
        s.post(runPath("http-platform"), "{}", token = "Bearer op").conforms().status shouldBe 202
        s.post(runPath("http-refuse"), """{"entityRef":42}""").closed(ClosedErrorCode.CONTRACT_VIOLATION)
        s.post(runPath("http-refuse"), "not json").closed(ClosedErrorCode.CONTRACT_VIOLATION)
        s.post("/internal/v1/jobs/http-refuse/stop", "{}").closed(ClosedErrorCode.NOT_FOUND)
        s.post(runPath("http-refuse"), null, method = "GET").closed(ClosedErrorCode.METHOD_NOT_ALLOWED)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `during catch-up a (job, entity) pauseJob asks the fallback for job-operate on that entity`(image: String) {
        val asked = mutableListOf<AuthorizationRequest>()
        var answer: (AuthorizationRequest) -> FallbackResult<AuthorityVerdict> =
            { FallbackResult.Found(AuthorityVerdict(it.entityRef == "E-001")) }
        val cold =
            AuthorityReplica(Clock.systemUTC(), fallback = { request, _ ->
                asked += request
                answer(request)
            })
        val s = Service(image, authority = cold)
        s.replica.runtime.register(job("http-catchup"), entities) { }
        val until = START.plus(Duration.ofHours(1))

        // grant present: allowed through the fallback, never refused for the empty set
        s
            .post(pausePath("http-catchup"), """{"entityRef":"E-001","until":"$until"}""", token = "Bearer eng-catchup")
            .conforms()
            .status shouldBe 200
        asked.last().action shouldBe JobCommandServlet.JOB_OPERATE
        asked.last().entityRef shouldBe "E-001"
        asked.last().tenant shouldBe TENANT_A
        // grant absent: refused by the fallback
        s
            .post(pausePath("http-catchup"), """{"entityRef":"E-002","until":"$until"}""", token = "Bearer eng-catchup")
            .closed(ClosedErrorCode.ACCESS_DENIED)
        asked.last().entityRef shouldBe "E-002"
        // the fallback fails too: a human action is denied
        answer = { FallbackResult.Unavailable }
        s
            .post(pausePath("http-catchup"), """{"entityRef":"E-001","until":"$until"}""", token = "Bearer eng-catchup")
            .closed(ClosedErrorCode.ACCESS_DENIED)
        asked.size shouldBe 3
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `pauseJob and resumeJob answer the key's own JobPauseState`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-pause", criticality = JobCriticality.DEADLINE_CRITICAL), entities) { }
        s.deadlines.due[e1] = START.plus(Duration.ofDays(2))
        val until = START.plus(Duration.ofHours(5))
        refusesInvalidPauses(s)

        val paused = s.post(pausePath("http-pause"), """{"entityRef":"E-001","until":"$until"}""").conforms()
        paused.status shouldBe 200
        paused.body.get("state").asString() shouldBe "PAUSED"
        Instant.parse(paused.body.get("until").asString()) shouldBe until
        paused.body.has("previousUntil") shouldBe false

        val later = until.plus(Duration.ofHours(1))
        val replaced = s.post(pausePath("http-pause"), """{"entityRef":"E-001","until":"$later"}""").conforms()
        Instant.parse(replaced.body.get("previousUntil").asString()) shouldBe until
        replaced.body.get("pausedAt") shouldBe paused.body.get("pausedAt")

        s
            .post(resumePath("http-pause"), """{"entityRef":"E-001"}""")
            .conforms()
            .body
            .get("state")
            .asString() shouldBe
            "ACTIVE"
        val again = s.post(resumePath("http-pause"), """{"entityRef":"E-001"}""").conforms()
        again.status shouldBe 200
        again.body.get("state").asString() shouldBe "ACTIVE"
        s.post(pausePath("http-pause"), """{"until":"$until"}""").coded("JOB-002", 403)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `the contract's x-operation reversibility is the one service-runtime declares per job command`(image: String) {
        JobCommand.entries.forEach { command ->
            val path =
                when (command) {
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
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `keys older than the retention are purged across tenants`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-purge"), entities) { }
        s.replica.runtime.register(job("http-purge-platform", scope = JobEntityScope.PLATFORM), body = { })
        val tenantKey = UUID.randomUUID().toString()
        val platformKey = UUID.randomUUID().toString()
        s.post(runPath("http-purge"), """{"entityRef":"E-001"}""", key = tenantKey).status shouldBe 202
        s.post(runPath("http-purge-platform"), "{}", key = platformKey, token = "Bearer op").status shouldBe 202

        fun stored(key: String) =
            s.h
                .rows(
                    "select count(*) as n from $SCHEMA.idempotency_key where idempotency_key = ?::uuid",
                    key,
                ).single()["n"]

        runtimeLoginPurgesNothing(s)
        stored(tenantKey) shouldBe 1L
        stored(platformKey) shouldBe 1L

        // the definer purge refuses a cut-off later than the floor and a batch outside its bounds (INV-Q-4070)
        s.h.fixture.runtimeDataSource().connection.use { runtime ->
            listOf(
                "now() - interval '23 hours'" to 10,
                "now() - interval '25 hours'" to 0,
                "now() - interval '25 hours'" to 10_001,
            ).forEach { (cutoff, batch) ->
                val refused =
                    assertThrows<SQLException> {
                        runtime
                            .prepareStatement(
                                "select $SCHEMA.${IdempotencySchema.PURGE_EXPIRED}($cutoff, $batch)",
                            ).use { it.executeQuery() }
                    }
                refused.sqlState shouldBe "22023"
            }
        }

        // within the retention the purge keeps both; past it, it deletes the expired keys of every tenant
        s.idempotency.purge() shouldBe 0
        // past the retention by the database clock (created_at moved back as the superuser): both expire
        s.h.fixture.runAsAdmin("update $SCHEMA.idempotency_key set created_at = created_at - interval '25 hours'")
        (s.idempotency.purge() >= 2) shouldBe true
        stored(tenantKey) shouldBe 0L
        stored(platformKey) shouldBe 0L
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a service clock skewed against the database neither fails the purge nor deletes a key early`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-skew"), entities) { }
        val key = UUID.randomUUID().toString()

        fun stored() =
            s.h
                .rows(
                    "select count(*) as n from $SCHEMA.idempotency_key where idempotency_key = ?::uuid",
                    key,
                ).single()["n"]
        // the key is stamped by the database, not by the service clock
        s.post(runPath("http-skew"), """{"entityRef":"E-001"}""", key = key).status shouldBe 202
        s.h
            .rows(
                "select count(*) as n from $SCHEMA.idempotency_key where idempotency_key = ?::uuid and created_at > now() - interval '1 hour'",
                key,
            ).single()["n"] shouldBe 1L

        // a service clock 3 days ahead of the database: the purge runs (no cut-off later than the floor) and keeps the key
        s.clock.advance(Duration.ofDays(3))
        s.idempotency.purge() // no 22023: the cut-off is never later than the floor
        stored() shouldBe 1L
        // 23 hours old by the database clock: still kept; 25 hours: purged
        s.h.fixture.runAsAdmin(
            "update $SCHEMA.idempotency_key set created_at = now() - interval '23 hours' where idempotency_key = '$key'",
        )
        s.idempotency.purge() // no 22023: the cut-off is never later than the floor
        stored() shouldBe 1L
        s.h.fixture.runAsAdmin(
            "update $SCHEMA.idempotency_key set created_at = now() - interval '25 hours' where idempotency_key = '$key'",
        )
        (s.idempotency.purge() >= 1) shouldBe true
        stored() shouldBe 0L
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the purge function is the sys role's definer, executable by the runtime login only`(image: String) {
        val s = Service(image)
        val function =
            s.h
                .rows(
                    "select pg_get_userbyid(p.proowner) as owner, p.prosecdef as definer, p.proconfig::text as " +
                        "config, " +
                        "has_function_privilege('public', p.oid, 'execute') as public_exec, " +
                        "has_function_privilege(?, p.oid, 'execute') as runtime_exec " +
                        "from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = ? and p.proname = ?",
                    OutboxPostgresFixture.APP,
                    SCHEMA,
                    IdempotencySchema.PURGE_EXPIRED,
                ).single()
        function["owner"] shouldBe OutboxPostgresFixture.SYS
        function["definer"] shouldBe true
        function["config"] shouldBe "{\"search_path=pg_catalog, $SCHEMA, pg_temp\"}"
        function["public_exec"] shouldBe false
        function["runtime_exec"] shouldBe true
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a request binds the token's sub, and a tenantless token only a platform-level target (INV-Q-4115)`(
        image: String,
    ) {
        val s = Service(image)
        s.replica.runtime.register(job("http-actor"), entities) { }
        s.replica.runtime.register(job("http-actor-platform", scope = JobEntityScope.PLATFORM), body = { })
        s.h.fixture.captureActors("$SCHEMA.outbox")
        s.h.fixture.captureActors("$SCHEMA.idempotency_key")

        // a tenant token: app.actor is the token's sub inside the request transaction (INV-REQ-854 b1)
        s.post(runPath("http-actor"), """{"entityRef":"E-001"}""").status shouldBe 202
        s.h.fixture.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer)
        s.h.fixture.capturedActors("$SCHEMA.outbox") shouldContain engineer

        // a tenantless token naming an entity of an ENTITY-scoped job: 403 SRT-004 before anything is bound or stored
        val refusedKey = UUID.randomUUID().toString()
        s
            .post(runPath("http-actor"), """{"entityRef":"E-001"}""", key = refusedKey, token = "Bearer op")
            .closed(ClosedErrorCode.ACCESS_DENIED)
        s
            .post(
                pausePath("http-actor"),
                """{"entityRef":"E-001","until":"2026-10-12T05:00:00Z"}""",
                token = "Bearer op",
            ).closed(ClosedErrorCode.ACCESS_DENIED)
        s.h
            .rows(
                "select count(*) as n from $SCHEMA.idempotency_key where idempotency_key = ?::uuid",
                refusedKey,
            ).single()["n"] shouldBe
            0L
        s.h.fixture.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer)

        // a tenantless token on a PLATFORM-scoped job, and a whole-job command: the platform tenant with its sub
        s.post(runPath("http-actor-platform"), "{}", token = "Bearer op").status shouldBe 202
        // past the INV-Q-4115 gate, an entityRef on a PLATFORM-scoped job is JobRuntime's contract violation (INV-Q-3537)
        s
            .post(runPath("http-actor-platform"), """{"entityRef":"E-001"}""", token = "Bearer op")
            .closed(ClosedErrorCode.CONTRACT_VIOLATION)
        s.h.fixture.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer, operator, operator)
        s.h
            .rows("select distinct tenant_id from $SCHEMA.idempotency_key where principal = ?", operator)
            .map { it["tenant_id"] } shouldBe listOf(TenantId.PLATFORM.value)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the auto-configured store purges on the runtime login as a ShedLock platform job (INV-Q-3798)`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("http-auto-purge"), entities) { }
        val key = UUID.randomUUID().toString()
        s.post(runPath("http-auto-purge"), """{"entityRef":"E-001"}""", key = key).status shouldBe 202

        fun stored() =
            s.h
                .rows(
                    "select count(*) as n from $SCHEMA.idempotency_key where idempotency_key = ?::uuid",
                    key,
                ).single()["n"]
        autoConfigured(s)
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(IdempotencyKeyPurgeJob::class.java).isRunning shouldBe true
                s.replica.runtime
                    .registered()
                    .map { it.name } shouldContain IdempotencyKeyPurgeJob.NAME
                stored() shouldBe 1L
                // the auto-configured store purges through the sys-owned definer; within the retention it keeps the key
                context.getBean(IdempotencyStore::class.java).purge()
                stored() shouldBe 1L
                // past it by the database clock, the next tick's run deletes the key, through the auto-configured store
                s.h.fixture.runAsAdmin(
                    "update $SCHEMA.idempotency_key set created_at = created_at - interval '25 hours' where idempotency_key = '$key'",
                )
                s.clock.advance(Duration.ofHours(25))
                s.replica.runtime.fire(IdempotencyKeyPurgeJob.NAME, s.clock.instant().plusSeconds(60)) shouldBe
                    mapOf(JobTarget.Whole to FireResult.RAN)
                stored() shouldBe 0L
                s.h
                    .runs(IdempotencyKeyPurgeJob.NAME)
                    .map { it["status"] }
                    .toSet() shouldBe setOf("SUCCEEDED")
                // a second poll in the same tick runs nothing
                (
                    s.replica.runtime
                        .fire(
                            IdempotencyKeyPurgeJob.NAME,
                            s.clock.instant().plusSeconds(60),
                        ).getValue(JobTarget.Whole) ==
                        FireResult.RAN
                ) shouldBe
                    false
            }
    }

    /** A pause without `until`, with a malformed one, one in the past and one beyond the deadline is refused. */
    private fun refusesInvalidPauses(s: Service) {
        s.post(pausePath("http-pause"), """{"entityRef":"E-001"}""").closed(ClosedErrorCode.CONTRACT_VIOLATION)
        s
            .post(
                pausePath("http-pause"),
                """{"entityRef":"E-001","until":"yesterday"}""",
            ).closed(ClosedErrorCode.CONTRACT_VIOLATION)
        s
            .post(
                pausePath("http-pause"),
                """{"entityRef":"E-001","until":"${START.minusSeconds(1)}"}""",
            ).coded("JOB-004", 422)
        s
            .post(
                pausePath("http-pause"),
                """{"entityRef":"E-001","until":"${START.plus(Duration.ofDays(3))}"}""",
            ).coded("JOB-003", 422)
    }

    private fun runtimeLoginPurgesNothing(s: Service) {
        // the runtime login's direct cross-tenant DELETE removes nothing: it holds no DELETE grant (INV-ADR-3968), and its
        // SELECT with no tenant bound sees no row (forced RLS)
        s.h.fixture.runtimeDataSource().connection.use { runtime ->
            assertThrows<SQLException> {
                runtime.prepareStatement("delete from $SCHEMA.idempotency_key").use { it.executeUpdate() }
            }.sqlState shouldBe "42501"
            runtime.prepareStatement("select count(*) from $SCHEMA.idempotency_key").use { q ->
                q.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            } shouldBe 0L
        }
    }

    /** The job command auto-configuration over [s]'s data source, runtime, scope, principals and clock. */
    private fun autoConfigured(s: Service): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JobCommandHttpAutoConfiguration::class.java))
            .withBean("dataSource", DataSource::class.java, { s.h.fixture.appDataSource() }, { it.isPrimary = true })
            .withBean(JobRuntime::class.java, { s.replica.runtime })
            .withBean(TenantScope::class.java, { s.replica.scope })
            .withBean(EndUserPrincipalResolver::class.java, { principals })
            .withBean(Clock::class.java, { s.clock })
            .withPropertyValues("gst.service=$SERVICE", "service-runtime.idempotency.schema=$SCHEMA")

    private companion object {
        /** The app pool's maximum in the pool test (INV-T-3947). */
        const val POOL_MAX = 4
    }
}
