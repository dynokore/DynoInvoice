package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SERVICE
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.START
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-AC-2406 over PostgreSQL 17 and 18: `submission-svc` runs three replicas with an hourly readiness-check
 * job for three hours, and the replica holding the lock is killed during the second hour. Exactly three runs
 * are recorded, one per hour; after the kill another replica takes the lock and the next run happens once.
 * The kill is a replica whose run never returns: its lock and its run row stay as a dead process leaves them.
 */
class LeaderElectionIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-2406 an hourly job runs once per hour across three replicas, also after the holder dies`(
        image: String,
    ) {
        JobsHarness(image).use { h ->
            val clock = MutableClock(START)
            val lease = Duration.ofSeconds(2) // lockAtMostFor, short so the test need not wait ten minutes
            val def = job("readiness-check", scope = JobEntityScope.PLATFORM, lockAtMostFor = lease)
            val killed = CountDownLatch(1)
            val holding = CountDownLatch(1)
            val replicas = (1..3).map { h.replica("submission-svc-$it", clock) }
            replicas.forEachIndexed { index, replica ->
                replica.runtime.register(def) { context ->
                    if (index == 0 && context.scheduledFor == START.plus(Duration.ofHours(1)).minusSeconds(5)) {
                        holding.countDown()
                        killed.await() // replica 1 dies here, holding the lock with its run in progress
                    }
                }
            }
            val pool = Executors.newFixedThreadPool(3)

            fun fireTogether(of: List<JobsHarness.Replica>) =
                of
                    .map { r ->
                        pool.submit<FireResult> { fireOnce(r, def.name) }
                    }.map { it.get(30, TimeUnit.SECONDS) }

            // Hour 1: all three fire at once; exactly one runs.
            fireTogether(replicas).count { it == FireResult.RAN } shouldBe 1

            // Hour 2: replica 1 takes the lock and dies during its run; the others find the lock taken.
            clock.advance(Duration.ofHours(1))
            val dying = pool.submit { replicas[0].runtime.fire(def.name) }
            holding.await(30, TimeUnit.SECONDS) shouldBe true
            replicas
                .drop(1)
                .map { fireOnce(it, def.name) }
                .count { it == FireResult.RAN } shouldBe 0

            // Hour 3: the dead replica's lease lapses; the sweeper records its run, and one replica runs hour 3.
            Thread.sleep(lease.plusMillis(500).toMillis())
            clock.advance(Duration.ofHours(1))
            replicas[1].runtime.sweepAbandoned() shouldBe 1
            val third = fireTogether(replicas.drop(1))
            third.count { it == FireResult.RAN } shouldBe 1

            // The dead replica's body returning late changes nothing: its run was already recorded.
            killed.countDown()
            dying.get(30, TimeUnit.SECONDS)
            pool.shutdown()

            val runs = h.runs(def.name)
            runs.map { it["tick"].toString().substring(11, 13) } shouldContainExactly listOf("00", "01", "02")
            runs.map { it["status"] } shouldContainExactly listOf("SUCCEEDED", "FAILED", "SUCCEEDED")
            runs[1]["reason"] shouldBe JobRuntime.ABANDONED
            h.events(JobPayloads.JOB_RUN_RECORDED, "$SERVICE/${def.name}") shouldHaveSize 3
        }
    }

    /** One replica's fire of [name]: its single target's result. */
    private fun fireOnce(
        replica: JobsHarness.Replica,
        name: String,
    ): FireResult =
        replica.runtime
            .fire(name)
            .values
            .single()
}
