package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.rules.bootstrap.egress.EgressGuard
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

private const val EGRESS_TYPE = "SCHEMATRON"
private const val EGRESS_CALLER = "console-bff"
private const val EGRESS_DB_PORT = 5432
private const val EGRESS_DRY_RUN_KEY = "pack.dry-run"
private const val EGRESS_APPROVE_KEY = "regulatory-pack.approve"
private const val EGRESS_IMPORT_KEY = "regulatory-pack.import-verify"
private const val EGRESS_ENTITY = "E-EGRESS"
private const val EGRESS_PERIOD = "2026-Q3"
private const val POLL_PAUSE = 100L
private const val POLL_TRIES = 100
private const val EGRESS_DOC = "0d000000-0000-4000-8000-0000000000e1"
private const val EGRESS_DOC_2 = "0d000000-0000-4000-8000-0000000000e2"

private fun egressDoc(uuid: String): Map<String, Any?> =
    mapOf(
        "periodRef" to EGRESS_PERIOD,
        "docUuid" to uuid,
        "entityRef" to EGRESS_ENTITY,
        "transactionDate" to "2026-07-01",
        "internalTaxCode" to "STD",
        "documentFunction" to "INVOICE",
        "supplyType" to "STANDARD_RATED",
        "taxableValue" to "100.00",
        "gstValue" to "9.00",
    )

/** The sample, calendar and evaluation fixtures of the delta run; the permission and the registry are the real ones. */
@TestConfiguration(proxyBeanMethods = false)
class EgressDryRunFixtures {
    @Bean fun entitySamples() = EntityAwareSamples()

    @Bean fun fixturePeriods() = FixtureLastCompletePeriodPort()

    @Bean fun fixtureEvaluation() = FixtureEvaluation()
}

/**
 * INV-REQ-1081, INV-AC-2077: in a private deployment a regulatory pack that `importRegulatoryPack` staged cannot be
 * armed before its local dry-run delta exists, the delta run makes no outbound connection (the [EgressGuard] records
 * every `Socket` and `SocketChannel` connect of the test JVM; only the loopback address and the test database are
 * allowlisted), and after the approval by another user the arming by the holder of the arming permission is accepted.
 */
@SpringBootTest(
    classes = [
        PolicyFixtures::class,
        ImportFixtures::class,
        ArmingFixtures::class,
        EgressDryRunFixtures::class,
        RulesApplication::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Suppress("LongParameterList") // the constructor injects the beans of the harness and of the fixtures
class RegulatoryPackDeltaEgressBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired replica: AuthorityReplica,
    @Autowired clock: MovableClock,
    @Autowired context: ApplicationContext,
    @Autowired policy: PolicyReplica,
    @Autowired private val structure: StructureReplica,
    @Autowired private val samples: EntityAwareSamples,
    @Autowired private val periods: FixtureLastCompletePeriodPort,
) : ArmingHarness(port, replica, clock, context, policy) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            register(registry)
            registry.add("gst.form") { "private" }
            registry.add("rules.dry-run.sample-wait") { "PT2S" }
            registry.add("service-runtime.idempotency.schema") { "rules" }
        }
    }

    @AfterEach
    fun reset() {
        samples.clear()
        periods.reset()
    }

    private fun bearer(user: String) = "Bearer $user|$tenant|"

    private fun dryRun(
        ref: String,
        user: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI("http://localhost:$port/internal/v1/rules/packs/$ref/dry-runs"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .header("Authorization", bearer(user))
                .header("X-Workload-Token", workloadToken(EGRESS_CALLER))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun stateOf(
        ref: String,
        runId: String,
        user: String,
    ): String {
        repeat(POLL_TRIES) {
            val answer =
                http.send(
                    HttpRequest
                        .newBuilder(URI("http://localhost:$port/internal/v1/rules/packs/$ref/dry-runs/$runId"))
                        .header("Authorization", bearer(user))
                        .header("X-Workload-Token", workloadToken(EGRESS_CALLER))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            val state = JsonMapper().readTree(answer.body()).get("state")?.asString()
            if (answer.statusCode() == 200 && state != "RUNNING") return checkNotNull(state)
            Thread.sleep(POLL_PAUSE)
        }
        error("the dry run $runId did not end")
    }

    private fun registryRow(
        type: String,
        version: String,
    ) = scalar(
        "select status || '|' || effective_from::text from rules.pack_registry " +
            "where pack_type = ? and pack_version = ?",
        type,
        version,
    )

    /**
     * The delta run of the staged pack with every connect of the test JVM recorded. The fixture sample adapter and the
     * test open their own connections to the database, and a driver may try the second address of a host that has two,
     * so every address of the database host is admitted, each on the mapped port only.
     */
    private fun guardedDryRun(
        ref: String,
        v: String,
    ) {
        val dbPort = PostgresRules.pg.getMappedPort(EGRESS_DB_PORT)
        val db = InetAddress.getAllByName(PostgresRules.pg.host).map { EgressGuard.Allowed(it.hostAddress, dbPort) }
        EgressGuard.install(*db.toTypedArray()).use { guard ->
            val answer = dryRun(ref, "u-dry")
            withClue(answer.body()) { (answer.statusCode() == 200 || answer.statusCode() == 202) shouldBe true }
            val runId = checkNotNull(scalar("select id::text from rules.dry_run_delta where pack_version = ?", v))
            stateOf(ref, runId, "u-dry") shouldBe "SUCCEEDED"
            guard.assertNoViolations()
        }
    }

    @Test
    fun `INV-AC-2077 an imported pack is refused arming before its delta, the delta run has no egress, then it arms`() {
        grant("u-importer", roles(DeploymentForm.PRIVATE, EGRESS_IMPORT_KEY).first())
        grant("u-approver", roles(DeploymentForm.PRIVATE, EGRESS_APPROVE_KEY).first())
        grant("u-armer", armerOf(DeploymentForm.PRIVATE))
        grant("u-dry", roles(DeploymentForm.PRIVATE, EGRESS_DRY_RUN_KEY).first())
        seedApproved(EGRESS_TYPE, "2026-01-01", "ARMED")
        val effective = uniqueDate()
        val v = importSchematron("u-importer", effective)
        val ref = "$EGRESS_TYPE@$v"
        structure.accept(
            EntityProfileChanged(
                tenant,
                EGRESS_ENTITY,
                StructureNode(StructureNodeKind.ENTITY, null, false, "E", EGRESS_ENTITY, emptyList(), 1),
            ),
        )
        samples.serve(RegulatoryDocs(EGRESS_ENTITY, egressDoc(EGRESS_DOC), egressDoc(EGRESS_DOC_2)))

        // (1) before any delta exists the arming is refused and the registry row is unchanged
        val staged = registryRow(EGRESS_TYPE, v)
        val refused = arm("u-armer", EGRESS_TYPE, v, effective)
        withClue(refused.body()) {
            refused.statusCode() shouldBe 409
            refused.body() shouldContain "SRT-008"
        }
        registryRow(EGRESS_TYPE, v) shouldBe staged

        // (2) the delta run of the staged pack: only loopback and the test database are admitted
        guardedDryRun(ref, v)
        scalar("select state from rules.dry_run_delta where pack_version = ?", v) shouldBe "COMPLETE"

        // (3) the approval by a user other than the stager, then the arming by the holder of the arming permission
        val approved = call("$EGRESS_TYPE/$v/approval", "u-approver", null)
        withClue(approved.body()) { approved.statusCode() shouldBe 200 }
        ackAll(EGRESS_TYPE, v)
        val armed = arm("u-armer", EGRESS_TYPE, v, effective)
        withClue(armed.body()) { armed.statusCode() shouldBe 200 }
        registryRow(EGRESS_TYPE, v) shouldBe "ARMED|$effective"
    }
}
