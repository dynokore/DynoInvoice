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
import jakarta.servlet.Filter
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

/** The harness, the service and the answers of the job command HTTP tests (INV-T-2874). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class JobCommandHttpBase {
    protected val harnesses = ConcurrentHashMap<String, JobsHarness>()
    protected val mapper = ObjectMapper()
    protected val contracts = ContractValidator()
    protected val openapi = "service-runtime/openapi.yaml"
    protected val envelope = "service-runtime/schemas/error-envelope.schema.json"
    protected val keyInvalid = ClosedErrorCode.IDEMPOTENCY_KEY_INVALID

    protected fun harness(image: String) =
        harnesses.computeIfAbsent(image) {
            JobsHarness(
                it,
                IdempotencySchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS),
                mapOf("$SCHEMA.idempotency_key" to TableClass.TENANT),
                listOf("$SCHEMA.outbox", "$SCHEMA.job_run", "$SCHEMA.idempotency_key"),
            )
        }

    @AfterAll
    fun close() = harnesses.values.forEach { it.close() }

    protected val operator = "platform-op"
    protected val engineer = "eng-e1"
    protected val authorizer = FakeAuthorizer(mapOf(engineer to setOf(E1)), setOf(operator))

    /** Tokens of the test: the engineer's covers E-001 and E-002 but it holds a grant on E-001 only. */
    protected val principals =
        EndUserPrincipalResolver { header ->
            when (header) {
                "Bearer eng" -> EndUserPrincipal(engineer, TENANT_A, setOf(E1, E2))

                "Bearer op" -> EndUserPrincipal(operator, null, emptySet())

                // the authority replica is catching up: the entity set is not current (INV-Q-3771)
                "Bearer eng-catchup" -> EndUserPrincipal(engineer, TENANT_A, emptySet(), entitySetCurrent = false)

                else -> null
            }
        }

    protected inner class Service(
        image: String,
        authority: AuthorityReplica? = null,
        poolMaximum: Int = TestPoolFixture.DEFAULT_MAXIMUM,
        connectionTimeoutMillis: Long = TestPoolFixture.DEFAULT_TIMEOUT_MILLIS,
        commands: ((JobCommands) -> JobCommands)? = null,
    ) {
        val h = harness(image)
        val clock = MutableClock(START)
        val deadlines = MovableDeadlines()
        val replica =
            h.replica(
                "svc-1",
                clock,
                authorizer,
                deadlines,
                JobsHarness.ReplicaExtras(poolMaximum = poolMaximum, connectionTimeoutMillis = connectionTimeoutMillis),
            )
        val idempotency = IdempotencyStore(replica.scope, replica.dsl, SCHEMA)
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
            front: Filter? = null,
        ): Answer {
            val request = MockHttpServletRequest(method, path)
            token?.let { request.addHeader("Authorization", it) }
            key?.let { request.addHeader("Idempotency-Key", it) }
            body?.let {
                request.contentType = "application/json"
                request.setContent(it.toByteArray())
            }
            val response = MockHttpServletResponse()
            BindingFront.serve(front, servlet, request, response)
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

    protected inner class Answer(
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

    protected val e1 = JobTarget.Entity(TENANT_A, E1)
    protected val e2 = JobTarget.Entity(TENANT_A, E2)
    protected val entities = JobEntitySource { listOf(e1, e2) }

    protected fun runPath(job: String) = "/internal/v1/jobs/$job/run"

    protected fun pausePath(job: String) = "/internal/v1/jobs/$job/pause"

    protected fun resumePath(job: String) = "/internal/v1/jobs/$job/resume"

    /** A pause without `until`, with a malformed one, one in the past and one beyond the deadline is refused. */
    protected fun refusesInvalidPauses(s: Service) {
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
}
