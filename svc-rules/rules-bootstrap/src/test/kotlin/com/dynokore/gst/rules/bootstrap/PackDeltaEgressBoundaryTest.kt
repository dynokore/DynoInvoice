package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.bootstrap.egress.EgressGuard
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress

private const val DB_PORT = 5432
private const val CONSOLE = "console-bff"
private const val INSTALLATION_FACT = """{"distinctTaxApprover":true,"recordedAt":"2026-10-25T08:00:00Z"}"""
private const val DOC = "0d000000-0000-4000-8000-0000000000a1"

/**
 * INV-REQ-1081, INV-AC-2077: in a private deployment (`gst.form=private`) a candidate pack cannot be approved before
 * its local dry-run delta exists, the delta run makes no outbound connection (the [EgressGuard] sees every `Socket` and
 * `SocketChannel` connect of the test JVM and only the loopback address and the test database are allowlisted), and
 * after the delta the approval is accepted. The pack here is a mapping pack of the dry-run operation of INV-T-4974; the
 * staging of an imported regulatory pack waits for the open question INV-Q-22336 (the events of the staging).
 */
@SpringBootTest(
    classes = [DryRunFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "gst.form=private",
        "rules.dry-run.sample-wait=PT2S",
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$CONSOLE",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CONSOLE",
    ],
)
class PackDeltaEgressBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired private val samples: FixtureHistoricalSamplePort,
    @Autowired private val periods: FixtureLastCompletePeriodPort,
    @Autowired private val evaluation: FixtureEvaluation,
) : DryRunSupport(port) {
    @BeforeEach
    fun installationFact() {
        // the private form asks access-svc for the installation fact at activation (the current pack of the fixtures)
        accessStub.replies[AccessStubServer.FACT] = AccessStubServer.Reply(200, INSTALLATION_FACT)
    }

    @AfterEach
    fun reset() {
        accessStub.reset()
        samples.clear()
        periods.reset()
        evaluation.reset()
    }

    @Test
    fun `INV-AC-2077 arming is refused before the delta, the delta run has no egress, then approval passes`() {
        samples.serve("2026-Q3", listOf(item(DOC, "2026-Q3")))
        val packId = candidate()

        // before the delta: submit for approval is refused and nothing changes
        val before = snapshot()
        submit(packId).statusCode() shouldBe 409
        snapshot() shouldBe before

        // the delta run: every connect of the test JVM is recorded; only loopback and the test database are admitted
        val db = InetAddress.getByName(PostgresRules.pg.host).hostAddress
        EgressGuard.install(EgressGuard.Allowed(db, PostgresRules.pg.getMappedPort(DB_PORT))).use { guard ->
            finished(packId).get("state").asString() shouldBe "SUCCEEDED"
            guard.assertNoViolations()
        }

        // after the delta: the second attempt is accepted
        ok(submit(packId)).get("status").asString() shouldBe "SUBMITTED"
        ok(approve(packId)).get("status").asString() shouldBe "APPROVED"
    }

    companion object {
        private val accessStub = AccessStubServer()

        @JvmStatic
        @DynamicPropertySource
        fun accessUrl(registry: DynamicPropertyRegistry) {
            registry.add("rules.access-svc-url") { accessStub.url }
        }
    }
}
