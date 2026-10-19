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

/** The idempotency-key purge and sub-binding tests of the job command HTTP surface (INV-T-2874). */
class JobCommandPurgeIT : JobCommandHttpBase() {
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
                .keyRows(key)
                .size
                .toLong()

        runtimeLoginPurgesNothing(s)
        stored(tenantKey) shouldBe 1L
        stored(platformKey) shouldBe 1L

        // the definer purge refuses a cut-off later than the floor and a batch outside its bounds (INV-Q-4070)
        listOf(
            OffsetDateTime.now().minusHours(23) to 10,
            OffsetDateTime.now().minusHours(25) to 0,
            OffsetDateTime.now().minusHours(25) to 10_001,
        ).forEach { (cutoff, batch) ->
            val refused =
                assertThrows<Exception> {
                    s.replica.tenants.bindPlatform(JobsHarness.TEST_ACTOR) {
                        s.replica.tx.inTransaction {
                            s.replica.dsl
                                .select(
                                    DSL.function(
                                        DSL.name(SCHEMA, IdempotencySchema.PURGE_EXPIRED),
                                        SQLDataType.BIGINT,
                                        DSL.`val`(cutoff),
                                        DSL.`val`(batch),
                                    ),
                                ).fetch()
                        }
                    }
                }
            s.replica.tx.sqlStateOf(refused) shouldBe "22023"
        }

        // within the retention the purge keeps both; past it, it deletes the expired keys of every tenant
        s.idempotency.purge() shouldBe 0
        // past the retention by the database clock (created_at moved back as the superuser): both expire
        s.h.ageKeys(Duration.ofHours(25))
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
                .keyRows(key)
                .size
                .toLong()
        // the key is stamped by the database, not by the service clock
        s.post(runPath("http-skew"), """{"entityRef":"E-001"}""", key = key).status shouldBe 202
        s.h
            .keyRows(key)
            .count { (it["created_at"] as OffsetDateTime).isAfter(OffsetDateTime.now().minusHours(1)) } shouldBe 1

        // a service clock 3 days ahead of the database: the purge runs (no cut-off later than the floor) and keeps the
        // key
        s.clock.advance(Duration.ofDays(3))
        s.idempotency.purge() // no 22023: the cut-off is never later than the floor
        stored() shouldBe 1L
        // 23 hours old by the database clock: still kept; 25 hours: purged
        s.h.setKeyAge(key, Duration.ofHours(23))
        s.idempotency.purge() // no 22023: the cut-off is never later than the floor
        stored() shouldBe 1L
        s.h.setKeyAge(key, Duration.ofHours(25))
        (s.idempotency.purge() >= 1) shouldBe true
        stored() shouldBe 0L
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the purge function is the sys role's definer, executable by the runtime login only`(image: String) {
        val s = Service(image)
        val function =
            s.h.admin.asPlatform {
                val namespace =
                    select(
                        PG_NAMESPACE_OID,
                    ).from(PG_NAMESPACE).where(PG_NAMESPACE_NAME.eq(SCHEMA)).fetchOne(PG_NAMESPACE_OID)
                select(
                    DSL.function(DSL.name("pg_get_userbyid"), SQLDataType.VARCHAR, PG_PROC_OWNER),
                    PG_PROC_DEFINER,
                    PG_PROC_CONFIG.cast(SQLDataType.CLOB),
                    DSL.function(
                        DSL.name("has_function_privilege"),
                        SQLDataType.BOOLEAN,
                        DSL.`val`("public"),
                        PG_PROC_OID,
                        DSL.`val`("execute"),
                    ),
                    DSL.function(
                        DSL.name("has_function_privilege"),
                        SQLDataType.BOOLEAN,
                        DSL.`val`(OutboxPostgresFixture.APP),
                        PG_PROC_OID,
                        DSL.`val`("execute"),
                    ),
                ).from(PG_PROC)
                    .where(PG_PROC_NAMESPACE.eq(namespace).and(PG_PROC_NAME.eq(IdempotencySchema.PURGE_EXPIRED)))
                    .fetchSingle()
                    .intoList()
            }
        function[0] shouldBe OutboxPostgresFixture.SYS
        function[1] shouldBe true
        function[2] shouldBe "{\"search_path=pg_catalog, $SCHEMA, pg_temp\"}"
        function[3] shouldBe false
        function[4] shouldBe true
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a request binds the token's sub, and a tenantless token only a platform-level target (INV-Q-4115)`(
        image: String,
    ) {
        val s = Service(image)
        s.replica.runtime.register(job("http-actor"), entities) { }
        s.replica.runtime.register(job("http-actor-platform", scope = JobEntityScope.PLATFORM), body = { })
        s.h.captureActors("$SCHEMA.outbox")
        s.h.captureActors("$SCHEMA.idempotency_key")

        // a tenant token: app.actor is the token's sub inside the request transaction (INV-REQ-854 b1)
        s.post(runPath("http-actor"), """{"entityRef":"E-001"}""").status shouldBe 202
        s.h.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer)
        s.h.capturedActors("$SCHEMA.outbox") shouldContain engineer

        // a tenantless token naming an entity of an ENTITY-scoped job: 403 SRT-004 before anything is bound or stored
        val refusedKey = UUID.randomUUID().toString()
        s
            .post(runPath("http-actor"), """{"entityRef":"E-001"}""", key = refusedKey, token = "Bearer op")
            .closed(ClosedErrorCode.ACCESS_DENIED)
        s
            .post(
                pausePath("http-actor"),
                """{"entityRef":"E-001","until":"${START.plus(Duration.ofHours(5))}"}""",
                token = "Bearer op",
            ).closed(ClosedErrorCode.ACCESS_DENIED)
        s.h.keyRows(refusedKey) shouldHaveSize 0
        s.h.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer)

        // a tenantless token on a PLATFORM-scoped job, and a whole-job command: the platform tenant with its sub
        s.post(runPath("http-actor-platform"), "{}", token = "Bearer op").status shouldBe 202
        // past the INV-Q-4115 gate, an entityRef on a PLATFORM-scoped job is JobRuntime's contract violation
        // (INV-Q-3537)
        s
            .post(runPath("http-actor-platform"), """{"entityRef":"E-001"}""", token = "Bearer op")
            .closed(ClosedErrorCode.CONTRACT_VIOLATION)
        s.h.capturedActors("$SCHEMA.idempotency_key") shouldBe listOf(engineer, operator, operator)
        s.h
            .rows("$SCHEMA.idempotency_key", "principal" to operator)
            .map { it["tenant_id"] }
            .distinct() shouldBe listOf(TenantId.PLATFORM.value)
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
                .keyRows(key)
                .size
                .toLong()
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
                s.h.ageKeys(Duration.ofHours(25), key)
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

    private fun runtimeLoginPurgesNothing(s: Service) {
        // the runtime login's direct cross-tenant DELETE removes nothing: it holds no DELETE grant (INV-ADR-3968), and
        // its
        // SELECT with no tenant bound sees no row (forced RLS)
        val table = "$SCHEMA.idempotency_key"
        val runtime = s.h.runtimeLogin(table)
        val refused =
            assertThrows<Exception> {
                runtime.asPlatform {
                    deleteFrom(
                        DSL.table(DSL.name(SCHEMA, "idempotency_key")),
                    ).execute()
                }
            }
        runtime.runner.sqlStateOf(refused) shouldBe "42501"
        // a tenant that stored nothing sees nothing: the policy limits every read to the bound tenant
        runtime.asTenant(TenantId(UUID.randomUUID())) {
            fetchCount(DSL.table(DSL.name(SCHEMA, "idempotency_key")))
        } shouldBe 0
    }

    /** The job command auto-configuration over [s]'s data source, runtime, scope, principals and clock. */
    private fun autoConfigured(s: Service): ApplicationContextRunner =
        s.replica
            .withDataSourceBean(ApplicationContextRunner())
            .withConfiguration(AutoConfigurations.of(JobCommandHttpAutoConfiguration::class.java))
            .withBean(JobRuntime::class.java, { s.replica.runtime })
            .withBean(org.jooq.DSLContext::class.java, { s.replica.dsl })
            .withBean(TenantScope::class.java, { s.replica.scope })
            .withBean(EndUserPrincipalResolver::class.java, { principals })
            .withBean(Clock::class.java, { s.clock })
            .withPropertyValues("gst.service=$SERVICE", "service-runtime.idempotency.schema=$SCHEMA")
}
