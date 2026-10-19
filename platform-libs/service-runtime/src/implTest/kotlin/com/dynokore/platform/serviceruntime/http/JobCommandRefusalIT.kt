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

/** The refusal, pause-state and contract tests of the job command HTTP surface (INV-T-2874). */
class JobCommandRefusalIT : JobCommandHttpBase() {
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

    @Test
    fun `the contract's x-operation reversibility is the one service-runtime declares per job command`() {
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
}
