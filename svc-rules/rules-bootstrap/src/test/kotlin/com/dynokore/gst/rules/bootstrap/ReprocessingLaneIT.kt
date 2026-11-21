package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

private const val LIMIT = 3
private const val POPULATION = 8

@SpringBootTest(
    classes = [
        PolicyFixtures::class,
        ImportFixtures::class,
        ArmingFixtures::class,
        CorrectionFixtures::class,
        RulesApplication::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ReprocessingLaneIT(
    @LocalServerPort port: Int,
    @Autowired replica: AuthorityReplica,
    @Autowired clock: MovableClock,
    @Autowired context: ApplicationContext,
    @Autowired policy: PolicyReplica,
) : ReprocessingHarness(port, replica, clock, context, policy) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registerCorrection(registry)
            registry.add("rules.reprocessing.rate-limit.permits") { LIMIT.toString() }
            registry.add("rules.reprocessing.rate-limit.window") { "PT10M" }
        }
    }

    @Test
    fun `INV-AC-23934 every item of the run is on the reprocessing lane and the live document goes first`() {
        // the window row is platform-wide and shared with the other classes of this database: start from none
        exec("delete from rules.lane_rate_window")
        val id = startRun()
        deliver(id, 1, true, List(POPULATION) { PageDoc(taxCode = "CHANGE", acknowledged = true) }) shouldBe
            com.dynokore.platform.outboxandqueue.HandleOutcome.APPLIED
        // the live document is a fixture item of the test; rules-svc never writes lane LIVE
        exec(
            "insert into rules.work_queue (tenant_id, partition_key, lane, consumer, payload) " +
                "values (?::uuid, 'live-doc', 'LIVE', 'rules-reprocessing', '{}'::jsonb)",
            tenant.toString(),
        )
        scalar("select count(*) from rules.work_queue where lane = 'LIVE' and partition_key <> 'live-doc'") shouldBe "0"
        scalar(
            "select count(*) from rules.work_queue where lane = 'REPROCESSING' and payload->>'packType' = ?",
            type,
        ) shouldBe POPULATION.toString()
        val queue = context.getBean(ReprocessingWorkQueue::class.java).queue
        val window = ArrayList<Lane>()
        while (true) window += (queue.claimNext() ?: break).lane
        window.first() shouldBe Lane.LIVE
        window.count { it == Lane.REPROCESSING } shouldBe LIMIT
        window.size shouldBe LIMIT + 1
        // the next window allows the same number again, never more
        // the window is counted on the database clock: age the stored window past its end
        exec("update rules.lane_rate_window set window_start = window_start - interval '11 minutes'")
        val next = ArrayList<Lane>()
        while (true) next += (queue.claimNext() ?: break).lane
        next.count { it == Lane.REPROCESSING } shouldBe LIMIT
        next.none { it == Lane.LIVE } shouldBe true
    }
}
