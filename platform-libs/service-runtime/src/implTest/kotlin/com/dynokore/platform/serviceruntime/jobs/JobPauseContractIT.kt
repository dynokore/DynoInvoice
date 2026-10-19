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

/** The pause bounds, retention and row-level-security tests of the job contract (STK-14, STK-15). */
class JobPauseContractIT : JobContractBase() {
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
        val short = h.replica("svc-1", clock, extras = JobsHarness.ReplicaExtras(retention = Duration.ofMinutes(30)))
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
        val replica = h.replica("svc-1", past, extras = JobsHarness.ReplicaExtras(retention = Duration.ofDays(2)))
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
