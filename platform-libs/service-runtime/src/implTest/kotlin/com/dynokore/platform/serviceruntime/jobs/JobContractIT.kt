package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.AUDIT
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E1
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E2
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SERVICE
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.START
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_A
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_B
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.request
import com.dynokore.platform.serviceruntime.testing.PG_CLASS
import com.dynokore.platform.serviceruntime.testing.PG_CLASS_FORCE
import com.dynokore.platform.serviceruntime.testing.PG_CLASS_NAME
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The job contract over PostgreSQL 17 and 18 (STK-14, STK-15): INV-AC-2126, INV-AC-3040, INV-AC-3041,
 * INV-AC-1149 and the pause, resume and retention rules of INV-REQ-1138 and the T30 slot answers. Each test
 * uses its own job names, so the tests share one container per image.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobContractIT {
    private val harnesses = ConcurrentHashMap<String, JobsHarness>()

    private fun harness(image: String) = harnesses.computeIfAbsent(image) { JobsHarness(it) }

    @AfterAll
    fun close() = harnesses.values.forEach { it.close() }

    private val e1 = JobTarget.Entity(TENANT_A, E1)
    private val e2 = JobTarget.Entity(TENANT_B, E2)

    private fun <T> JobsHarness.Replica.inA(block: JobRuntime.() -> T): T = asTenant(TENANT_A) { runtime.block() }

    private val entities = JobEntitySource { listOf(e1, e2) }
    private val operator = "platform-op"
    private val engineer = "eng-e1"
    private val authorizer = FakeAuthorizer(mapOf(engineer to setOf(E1), "eng-e2" to setOf(E2)), setOf(operator))

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-2126 a declared job is registered with its contract and records each run`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START))
        val def = job("ac2126-check", criticality = JobCriticality.DEADLINE_CRITICAL)
        replica.runtime.register(def, entities) { }
        h.captureActors("submission.outbox")

        replica.runtime.announce()
        // start-up is service-level background work: `svc:<svc>`; a scheduled run binds `svc:<svc>/job:<job>`
        // (owner ruling INV-Q-4344 (b))
        h.capturedActors("submission.outbox").toSet() shouldBe setOf("svc:submission-svc")
        replica.runtime.fire(def.name) shouldBe mapOf(e1 to FireResult.RAN, e2 to FireResult.RAN)
        h.capturedActors("submission.outbox").toSet() shouldBe
            setOf("svc:submission-svc", "svc:submission-svc/job:ac2126-check")

        val registered =
            h
                .rows("submission.outbox", "type" to JobPayloads.JOB_REGISTERED)
                .filter { (it["data"] as String).contains(""""job": "${def.name}"""") }
        registered shouldHaveSize 1
        registered.single()["tenant_id"] shouldBe TenantId.PLATFORM.value
        val payload = registered.single()["data"] as String
        listOf(
            """"job": "ac2126-check"""",
            """"ownerService": "submission-svc"""",
            """"entityScope": "ENTITY"""",
            """"schedule": "0 0 * * * *"""",
            """"criticality": "DEADLINE_CRITICAL"""",
            """"tolerance": "PT15M"""",
        ).forEach { payload shouldContain it }

        val runs = h.events(JobPayloads.JOB_RUN_RECORDED, "$SERVICE/${def.name}")
        runs shouldHaveSize 2
        val e1 = runs.single { it["tenant_id"] == TENANT_A.value }
        e1["entity_ref"] shouldBe E1.value
        val data = e1["data"].toString()
        listOf(
            """"entityScope": "E-001"""",
            """"status": "SUCCEEDED"""",
            """"scheduledFor": "${START.truncatedTo(ChronoUnit.HOURS)}"""",
            """"nextRunAt": "${START.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1))}"""",
            """"criticality": "DEADLINE_CRITICAL"""",
        ).forEach { data shouldContain it }
        h.runs(def.name).map { it["status"] } shouldContainExactly listOf("SUCCEEDED", "SUCCEEDED")
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a failing body is a FAILED run with the exception class as reason, never its message`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START))
        val def = job("failing-job", scope = JobEntityScope.PLATFORM)
        replica.runtime.register(def) { throw IllegalStateException("invoice 4711 for ACME") }

        replica.runtime.fire(def.name) shouldBe mapOf(JobTarget.Whole to FireResult.RAN)

        val run = h.runs(def.name).single()
        run["status"] shouldBe "FAILED"
        run["reason"] shouldBe "java.lang.IllegalStateException"
        val event = h.events(JobPayloads.JOB_RUN_RECORDED, "$SERVICE/${def.name}").single()
        event["tenant_id"] shouldBe TenantId.PLATFORM.value
        event["data"].toString() shouldContain """"reason": "java.lang.IllegalStateException""""
        (event["data"].toString().contains("ACME")) shouldBe false
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-3040 a run-now repeated in the same run window has no second effect`(image: String) {
        val h = harness(image)
        val clock = MutableClock(START.plus(Duration.ofMinutes(12)))
        val first = h.replica("svc-1", clock, authorizer)
        val second = h.replica("svc-2", clock, FakeAuthorizer(mapOf("eng-2" to setOf(E1))))
        val transmitted = CopyOnWriteArrayList<JobTarget>()
        val def = job("ac3040-despatch")
        listOf(first, second).forEach { r -> r.runtime.register(def, entities) { transmitted += it.target } }

        val started = first.inA { runNow(request(engineer, def.name, E1)) }
        val again = first.inA { runNow(request(engineer, def.name, E1)) }
        val byOther = second.inA { runNow(request("eng-2", def.name, E1)) }

        val run = started.shouldBeInstanceOf<JobCommandOutcome.Done<JobRunState>>()
        run.created shouldBe true
        run.state.status shouldBe JobRunStatus.IN_PROGRESS
        run.state.scheduledFor shouldBe START.truncatedTo(ChronoUnit.HOURS)
        listOf(again, byOther).forEach {
            val repeat = it.shouldBeInstanceOf<JobCommandOutcome.Done<JobRunState>>()
            repeat.created shouldBe false
            repeat.state.repeat shouldBe true
            repeat.state.attempt shouldBe 1
            repeat.state.status shouldBe JobRunStatus.SUCCEEDED
        }
        transmitted shouldContainExactly listOf(e1)
        h.runs(def.name) shouldHaveSize 1
        h.events(JobPayloads.JOB_RUN_RECORDED, "$SERVICE/${def.name}") shouldHaveSize 1
        h.events(AUDIT, "$SERVICE/${def.name}") shouldHaveSize 1

        // INV-Q-3039: the scheduled run of the same interval is keyed apart and still runs.
        first.runtime.fire(def.name)[e1] shouldBe FireResult.RAN
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `after a FAILED run-now a new attempt in the same window is allowed`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START.plus(Duration.ofMinutes(5))), authorizer)
        var fail = true
        val def = job("rerun-after-failure")
        replica.runtime.register(def, entities) { check(!fail) }

        replica.inA { runNow(request(engineer, def.name, E1)) }
        fail = false
        val retry = replica.inA { runNow(request(engineer, def.name, E1)) }

        val done = retry.shouldBeInstanceOf<JobCommandOutcome.Done<JobRunState>>()
        done.created shouldBe true
        done.state.attempt shouldBe 2
        h.runs(def.name).map { it["status"] } shouldContainExactly listOf("FAILED", "SUCCEEDED")
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-3041 a tenant role runs only its entity's share and cannot run the whole job`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START), authorizer)
        val processed = CopyOnWriteArrayList<JobTarget>()
        val def = job("ac3041-despatch")
        replica.runtime.register(def, entities) { processed += it.target }

        replica
            .inA { runNow(request(engineer, def.name, E1)) }
            .shouldBeInstanceOf<JobCommandOutcome.Done<JobRunState>>()
        replica.inA { runNow(request(engineer, def.name, null)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)
        replica.inA { runNow(request(engineer, def.name, E2)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)

        processed shouldContainExactly listOf(e1)
        h.runs(def.name) shouldHaveSize 1

        // The platform operator role runs the whole job, under the platform tenant.
        replica
            .inA { runNow(request(operator, def.name, null)) }
            .shouldBeInstanceOf<JobCommandOutcome.Done<JobRunState>>()
            .state.target shouldBe JobTarget.Whole
        processed.last() shouldBe JobTarget.Whole
        h.runs(def.name).map { it["tenant_id"] } shouldContainExactlyInAnyOrder
            listOf(TENANT_A.value, TenantId.PLATFORM.value)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `commands on an unknown job or a PLATFORM-scoped job by a tenant role are refused`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START), authorizer)
        val platformJob = job("platform-only", scope = JobEntityScope.PLATFORM)
        replica.runtime.register(platformJob) { }

        replica.inA { runNow(request(engineer, "no-such-job", E1)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.JOB_UNKNOWN)
        replica.inA { runNow(request(engineer, platformJob.name, E1)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)
        replica.inA { pause(request(engineer, platformJob.name, null), START.plusSeconds(60)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)
        h.runs(platformJob.name) shouldHaveSize 0

        // INV-Q-3537: the platform operator giving an entity for a PLATFORM-scoped job is a contract violation.
        assertThrows<IllegalArgumentException> { replica.inA { runNow(request(operator, platformJob.name, E1)) } }
        h.runs(platformJob.name) shouldHaveSize 0
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3533 a run-now on a paused entity runs once, keeps the pause and audits it`(image: String) {
        val h = harness(image)
        val clock = MutableClock(START)
        val replica = h.replica("svc-1", clock, authorizer)
        val processed = CopyOnWriteArrayList<Pair<JobTarget, Boolean>>()
        val def = job("q3533-entity")
        replica.runtime.register(def, entities) { processed += it.target to it.skips(TENANT_A, E1) }
        val until = START.plus(Duration.ofHours(3))
        replica.inA { pause(request(engineer, def.name, E1), until) }

        val run = replica.inA { runNow(request(engineer, def.name, E1)) }

        run.shouldBeInstanceOf<JobCommandOutcome.Done<JobRunState>>().created shouldBe true
        processed shouldContainExactly listOf(e1 to false)
        val prior = h.events(AUDIT, "$SERVICE/${def.name}").map { it["data"].toString() }.last()
        prior shouldContain "runJobNow"
        prior shouldContain "pausesInForce"
        prior shouldContain "E-001"
        prior shouldContain "OVERRIDDEN"
        prior shouldContain until.toString()

        // The pause stays in force for scheduled firings.
        clock.advance(Duration.ofHours(1))
        replica.runtime.fire(def.name)[e1] shouldBe FireResult.SKIPPED_PAUSED
        h.rows("submission.job_pause", "job" to def.name).single()["ended_at"] shouldBe null
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3533 a tenant run-now under a job-wide pause it cannot lift is refused with JOB-002`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START), authorizer)
        val processed = CopyOnWriteArrayList<JobTarget>()
        val def = job("q3533-wide")
        replica.runtime.register(def, entities) { processed += it.target }
        replica.inA { pause(request(operator, def.name, null), START.plus(Duration.ofHours(2))) }

        replica.inA { runNow(request(engineer, def.name, E1)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)

        processed shouldHaveSize 0
        h.runs(def.name) shouldHaveSize 0
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3563 a whole-job run-now skips and lists the entity under a pause it cannot lift`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START), authorizer)
        val seen = CopyOnWriteArrayList<Pair<EntityRef, Boolean>>()
        val def = job("q3563-whole")
        replica.runtime.register(def, entities) { context ->
            entities.entities(def).forEach { seen += it.entity to context.skips(it.tenant, it.entity) }
        }
        val until = START.plus(Duration.ofHours(2))
        replica.inA { pause(request(engineer, def.name, E1), until) }
        replica.inA { pause(request(operator, def.name, null), until) }

        val run = replica.inA { runNow(request(operator, def.name, null)) }

        run.shouldBeInstanceOf<JobCommandOutcome.Done<JobRunState>>().state.target shouldBe JobTarget.Whole
        seen shouldContainExactly listOf(E1 to true, E2 to false)
        val prior = h.events(AUDIT, "$SERVICE/${def.name}").map { it["data"].toString() }.last()
        prior shouldContain "runJobNow"
        prior shouldContain "SKIPPED"
        prior shouldContain "E-001"
        prior shouldContain "OVERRIDDEN"
        (prior.contains("E-002")) shouldBe false
        h.runs(def.name).single()["status"] shouldBe "SUCCEEDED"
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3567 a whole-job run-now with every entity under a pause it cannot lift is refused`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START), authorizer)
        val processed = CopyOnWriteArrayList<JobTarget>()
        val def = job("q3567-covered")
        replica.runtime.register(def, entities) { processed += it.target }
        val until = START.plus(Duration.ofHours(2))
        replica.inA { pause(request(engineer, def.name, E1), until) }
        replica.asTenant(TENANT_B) { replica.runtime.pause(request("eng-e2", def.name, E2), until) }
        val auditsBefore = h.events(AUDIT, "$SERVICE/${def.name}").size

        replica.inA { runNow(request(operator, def.name, null)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)

        processed shouldHaveSize 0
        h.runs(def.name) shouldHaveSize 0
        h.events(JobPayloads.JOB_RUN_RECORDED, "$SERVICE/${def.name}") shouldHaveSize 0
        h.events(AUDIT, "$SERVICE/${def.name}") shouldHaveSize auditsBefore
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a pause or resume outside the caller's role is refused with JOB-002 and changes nothing`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START), authorizer)
        val def = job("pause-roles")
        replica.runtime.register(def, entities) { }
        val until = START.plus(Duration.ofHours(2))
        replica.inA { pause(request(operator, def.name, null), until) }
        replica.inA { pause(request(engineer, def.name, E1), until) }
        val audits = h.events(AUDIT, "$SERVICE/${def.name}").size

        replica.inA { resume(request(engineer, def.name, null)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)
        replica.inA { resume(request("eng-e2", def.name, E1)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)
        replica.inA { pause(request(engineer, def.name, E2), until) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)

        val pauses = h.rows("submission.job_pause", "job" to def.name)
        pauses.map { it["entity_ref"] } shouldContainExactlyInAnyOrder listOf(E1.value, EntityRef.PLATFORM.value)
        pauses.map { it["ended_at"] } shouldContainExactly listOf(null, null)
        h.events(AUDIT, "$SERVICE/${def.name}") shouldHaveSize audits
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3531 a pause of a PLATFORM-scoped deadline-critical job is bounded by the whole job's bound`(
        image: String,
    ) {
        val h = harness(image)
        val deadlines = MovableDeadlines(margin = Duration.ofHours(1))
        deadlines.due[JobTarget.Whole] = START.plus(Duration.ofHours(4))
        val replica = h.replica("svc-1", MutableClock(START), authorizer, deadlines)
        val def = job("q3531-platform", scope = JobEntityScope.PLATFORM, criticality = JobCriticality.DEADLINE_CRITICAL)
        replica.runtime.register(def) { }

        replica.inA {
            pause(
                request(operator, def.name, null),
                START.plus(Duration.ofHours(3)).plusSeconds(1),
            )
        } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.PAUSE_PAST_DEADLINE)
        replica
            .inA { pause(request(operator, def.name, null), START.plus(Duration.ofHours(3))) }
            .shouldBeInstanceOf<JobCommandOutcome.Done<JobPauseState>>()
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3531 a job-wide pause of a deadline-critical job is bounded by the earliest entity bound`(
        image: String,
    ) {
        val h = harness(image)
        val clock = MutableClock(START)
        val deadlines = MovableDeadlines(margin = Duration.ofHours(1))
        deadlines.due[JobTarget.Whole] = START.plus(Duration.ofHours(4)) // the earliest over E1 and E2
        deadlines.due[e1] = START.plus(Duration.ofHours(4))
        deadlines.due[e2] = START.plus(Duration.ofHours(9))
        val replica = h.replica("svc-1", clock, authorizer, deadlines)
        val def = job("q3531-wide", criticality = JobCriticality.DEADLINE_CRITICAL)
        replica.runtime.register(def, entities) { }

        replica.inA { pause(request(operator, def.name, null), START.plus(Duration.ofHours(5))) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.PAUSE_PAST_DEADLINE)
        replica
            .inA { pause(request(operator, def.name, null), START.plus(Duration.ofHours(3))) }
            .shouldBeInstanceOf<JobCommandOutcome.Done<JobPauseState>>()

        clock.advance(Duration.ofHours(1))
        replica.runtime
            .fire(def.name)
            .values
            .toSet() shouldBe setOf(FireResult.SKIPPED_PAUSED)

        // The earliest bound moves to START + 2 h: the job-wide pause lapses there for every entity.
        deadlines.due[JobTarget.Whole] = START.plus(Duration.ofHours(3))
        clock.advance(Duration.ofHours(1))
        replica.runtime
            .fire(def.name)
            .values
            .toSet() shouldBe setOf(FireResult.RAN)
        val ended = h.rows("submission.job_pause", "job" to def.name).single()
        JobsHarness.instantOf(ended["ended_at"]) shouldBe START.plus(Duration.ofHours(2))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a per-entity pause skips that entity, replaces its end time and resumes, each change audited once`(
        image: String,
    ) {
        val h = harness(image)
        val clock = MutableClock(START)
        val replica = h.replica("svc-1", clock, authorizer)
        val def = job("pause-entity")
        replica.runtime.register(def, entities) { }
        val until = START.plus(Duration.ofHours(3))

        val paused = replica.inA { pause(request(engineer, def.name, E1, "AP outage"), until) }
        paused shouldBe JobCommandOutcome.Done(JobPauseState(def.name, e1, true, until, START), created = true)
        replica
            .inA { pause(request(engineer, def.name, E1), until) }
            .shouldBeInstanceOf<JobCommandOutcome.Done<JobPauseState>>()
            .created shouldBe false

        clock.advance(Duration.ofHours(1))
        replica.runtime.fire(def.name) shouldBe
            mapOf(e1 to FireResult.SKIPPED_PAUSED, e2 to FireResult.RAN)

        val later = until.plus(Duration.ofHours(1))
        val replaced = replica.inA { pause(request(engineer, def.name, E1), later) }
        replaced shouldBe JobCommandOutcome.Done(JobPauseState(def.name, e1, true, later, START, until), created = true)

        val resumed = replica.inA { resume(request(engineer, def.name, E1)) }
        resumed shouldBe JobCommandOutcome.Done(JobPauseState(def.name, e1, false), created = true)
        replica
            .inA { resume(request(engineer, def.name, E1)) }
            .shouldBeInstanceOf<JobCommandOutcome.Done<JobPauseState>>()
            .created shouldBe false

        clock.advance(Duration.ofHours(1))
        replica.runtime.fire(def.name)[e1] shouldBe FireResult.RAN

        val audits = h.events(AUDIT, "$SERVICE/${def.name}").map { it["data"].toString() }
        audits shouldHaveSize 3
        audits[0] shouldContain """"action": "pauseJob""""
        audits[0] shouldContain "AP outage"
        audits[1] shouldContain """"until": "$until""""
        audits[2] shouldContain """"action": "resumeJob""""
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a job-wide pause is the platform operator's and skips every entity`(image: String) {
        val h = harness(image)
        val clock = MutableClock(START)
        val replica = h.replica("svc-1", clock, authorizer)
        val def = job("pause-wide")
        replica.runtime.register(def, entities) { }

        replica.inA { pause(request(engineer, def.name, null), START.plusSeconds(7200)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)
        replica
            .inA { pause(request(operator, def.name, null), START.plusSeconds(7200)) }
            .shouldBeInstanceOf<JobCommandOutcome.Done<JobPauseState>>()
            .state.target shouldBe JobTarget.Whole

        clock.advance(Duration.ofHours(1))
        replica.runtime
            .fire(def.name)
            .values
            .toSet() shouldBe setOf(FireResult.SKIPPED_PAUSED)
        clock.advance(Duration.ofHours(1))
        replica.runtime
            .fire(def.name)
            .values
            .toSet() shouldBe setOf(FireResult.RAN)
        val pause = h.rows("submission.job_pause", "job" to def.name).single()
        pause["tenant_id"] shouldBe TenantId.PLATFORM.value
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-1149 a deadline-critical pause past due date minus margin is refused and lapses if it moves`(
        image: String,
    ) {
        val h = harness(image)
        val clock = MutableClock(START)
        val deadlines = MovableDeadlines(margin = Duration.ofHours(1))
        val target = e1
        deadlines.due[target] = START.plus(Duration.ofHours(5))
        val replica = h.replica("svc-1", clock, authorizer, deadlines)
        val def = job("ac1149-submission", criticality = JobCriticality.DEADLINE_CRITICAL)
        replica.runtime.register(def, entities) { }

        replica.inA { pause(request(engineer, def.name, E1), START.plus(Duration.ofHours(4)).plusSeconds(1)) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.PAUSE_PAST_DEADLINE)
        replica.inA { pause(request(engineer, def.name, E1), START) } shouldBe
            JobCommandOutcome.Refused(JobErrorCode.PAUSE_END_NOT_FUTURE)
        replica
            .inA { pause(request(engineer, def.name, E1), START.plus(Duration.ofHours(4))) }
            .shouldBeInstanceOf<JobCommandOutcome.Done<JobPauseState>>()

        clock.advance(Duration.ofHours(1))
        replica.runtime.fire(def.name)[target] shouldBe FireResult.SKIPPED_PAUSED

        // The due date moves earlier: the bound is re-checked at the next firing and the pause lapses there.
        deadlines.due[target] = START.plus(Duration.ofHours(3))
        clock.advance(Duration.ofHours(1))
        replica.runtime.fire(def.name)[target] shouldBe FireResult.RAN
        val ended = h.rows("submission.job_pause", "job" to def.name).single()
        JobsHarness.instantOf(ended["ended_at"]) shouldBe START.plus(Duration.ofHours(2))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `retention refuses a window shorter than interval plus tolerance and purges old rows`(image: String) {
        val h = harness(image)
        val clock = MutableClock(START)
        val short = h.replica("svc-1", clock, retention = Duration.ofMinutes(30))
        assertThrows<IllegalArgumentException> {
            short.runtime.register(job("retention-hourly", scope = JobEntityScope.PLATFORM)) { }
        }

        // the purge's age predicate is in SQL against the database's now() (INV-Q-4346 (A)): runs 3 and 1 days old
        val past =
            MutableClock(
                Instant
                    .now()
                    .truncatedTo(ChronoUnit.HOURS)
                    .minus(Duration.ofDays(3))
                    .plusSeconds(5),
            )
        val replica = h.replica("svc-1", past, retention = Duration.ofDays(2))
        h.writeRetention(Duration.ofDays(2))
        try {
            replica.runtime.checkRetention()
            val def = job("retention-purge", scope = JobEntityScope.PLATFORM)
            replica.runtime.register(def) { }
            replica.runtime.fire(def.name)
            past.advance(Duration.ofDays(2))
            replica.runtime.fire(def.name)

            replica.runtime.purge() shouldBe 1

            h.runs(def.name).map { it["tick"].toString() } shouldHaveSize 1
        } finally {
            h.writeRetention(JobRuntime.DEFAULT_RETENTION)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `job_run and job_pause hold rows under forced row-level security`(image: String) {
        val h = harness(image)
        val replica = h.replica("svc-1", MutableClock(START), authorizer)
        val def = job("rls-check")
        replica.runtime.register(def, entities) { }
        replica.runtime.fire(def.name)

        // forced row-level security on the job tables (and none on shedlock): read from the catalog as the admin login
        val forced =
            h.admin.asPlatform {
                select(PG_CLASS_NAME, PG_CLASS_FORCE)
                    .from(PG_CLASS)
                    .where(PG_CLASS_NAME.`in`("job_run", "job_pause", "shedlock"))
                    .orderBy(PG_CLASS_NAME)
                    .fetch()
                    .associate { it.get(PG_CLASS_NAME) to it.get(PG_CLASS_FORCE) }
            }
        forced shouldBe mapOf("job_pause" to true, "job_run" to true, "shedlock" to false)
        val seenByA =
            replica.asTenant(TENANT_A) {
                replica.tx.inTransaction {
                    replica.dsl
                        .select(DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB))
                        .from(DSL.table(DSL.name("submission", "job_run")))
                        .where(DSL.field(DSL.name("job"), SQLDataType.CLOB).eq(def.name))
                        .fetch { EntityRef(it.value1()) }
                }
            }
        seenByA shouldContainExactly listOf(E1)
    }
}
