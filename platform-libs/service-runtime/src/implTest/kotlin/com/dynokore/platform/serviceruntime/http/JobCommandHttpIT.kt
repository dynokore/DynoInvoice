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
import com.dynokore.platform.serviceruntime.testing.PG_NAMESPACE
import com.dynokore.platform.serviceruntime.testing.PG_NAMESPACE_NAME
import com.dynokore.platform.serviceruntime.testing.PG_NAMESPACE_OID
import com.dynokore.platform.serviceruntime.testing.PG_PROC
import com.dynokore.platform.serviceruntime.testing.PG_PROC_CONFIG
import com.dynokore.platform.serviceruntime.testing.PG_PROC_DEFINER
import com.dynokore.platform.serviceruntime.testing.PG_PROC_NAME
import com.dynokore.platform.serviceruntime.testing.PG_PROC_NAMESPACE
import com.dynokore.platform.serviceruntime.testing.PG_PROC_OID
import com.dynokore.platform.serviceruntime.testing.PG_PROC_OWNER
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
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
import java.time.OffsetDateTime
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
class JobCommandHttpIT : JobCommandHttpBase() {
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
        s.h.keyRows(key).map { it["status"] } shouldBe listOf(202)
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
        harness(image)
        run {
            for (commands in listOf(POOL_MAX, 2 * POOL_MAX)) {
                val s =
                    Service(
                        image,
                        poolMaximum = POOL_MAX,
                        connectionTimeoutMillis = Duration.ofSeconds(2).toMillis(),
                    )
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
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a no-state refusal is recorded in its own one transaction and replayed`(image: String) {
        val s = Service(image)
        s.h.captureActors("$SCHEMA.idempotency_key")
        val key = UUID.randomUUID().toString()
        s.post(runPath("http-late"), """{"entityRef":"E-001"}""", key).coded("JOB-001", 404)
        s.h.keyRows(key).map { it["status"] } shouldBe listOf(404)
        // recorded in a transaction bound to the command's principal, never the service (INV-Q-4268)
        s.h.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer)
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

        fun records() = s.h.keyRows(key)

        s.post(runPath("http-tx"), """{"entityRef":"E-001"}""", key).closed(ClosedErrorCode.INTERNAL_ERROR)
        records() shouldHaveSize 0
        s.h.runs("http-tx").filter { it["trigger"] == "MANUAL" } shouldHaveSize 0
        s.h.events(JobsHarness.AUDIT, "submission-svc/http-tx") shouldHaveSize 0

        crash = false
        s.h.captureActors("$SCHEMA.idempotency_key")
        s.post(runPath("http-tx"), """{"entityRef":"E-001"}""", key).conforms().status shouldBe 202
        records().map { it["status"] } shouldBe listOf(202)
        // recorded in the command's own transaction, bound to the operator's principal (INV-Q-4268)
        s.h.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer)
        s.h.runs("http-tx").filter { it["trigger"] == "MANUAL" } shouldHaveSize 1
    }

    private companion object {
        /** The app pool's maximum in the pool test (INV-T-3947). */
        const val POOL_MAX = 4
    }
}
