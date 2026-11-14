package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.application.SilenceLimitPort
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.ReplicaFallbackDouble
import com.dynokore.gst.rules.served.subjectOf
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * INV-REQ-2468, INV-REQ-1080: the mounted shared `setPolicy` of VP-72 over the wired context. While VP-72 is
 * catalogued planned, every value is refused (POL-006), so a limit above 30 days and any limit other than 7 days
 * are refused, and the reader of rules-svc keeps the default of 7 days.
 */
@SpringBootTest(
    classes = [PolicyFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class SilenceLimitPolicyBoundaryTest(
    @LocalServerPort private val port: Int,
    @Autowired private val replica: AuthorityReplica,
    @Autowired private val clock: MovableClock,
    @Autowired private val limit: SilenceLimitPort,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresRules.register(registry)
            registry.add("service-runtime.modules.schema") { "rules" }
            registry.add("inv.tenancy.reference-tables") { "rules.pack_registry,rules.pack_entry_source" }
            registry.add("service-runtime.workload-identity.callers[0].service") { "console-bff" }
            registry.add("service-runtime.workload-identity.callers[0].subject") { subjectOf("console-bff") }
            registry.add("gst.form") { "private" }
        }

        private val versions =
            java.util.concurrent.atomic
                .AtomicLong(0)
    }

    @BeforeEach
    fun fresh() {
        ReplicaFallbackDouble.reset()
        replica.tracker.admitIncarnation(1)
        replica.onSnapshotComplete(0)
        replica.tracker.onApplied(versions.get())
    }

    private val tenant = TenantId(UUID.randomUUID())

    private fun grantOperator() {
        val role = OperationRoles.rolesFor(DeploymentForm.PRIVATE, "vp-72.set").keys.first()
        replica.accept(
            AuthorityChanged(
                tenant,
                "u-op",
                versions.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(
                    "g1",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        role,
                        GrantScope.TENANT,
                        tenant.toString(),
                        clock.instant().minus(Duration.ofHours(1)),
                        null,
                    ),
                ),
                1,
            ),
        )
    }

    private fun set(
        option: String,
        level: String = "TENANT",
    ): java.net.http.HttpResponse<String> {
        val scope = if (level == "TENANT") """"scopeRef":"$tenant",""" else ""
        val body =
            """{"scopeLevel":"$level",$scope"selected":[{"option":"$option"}],""" +
                """"effectiveDate":"${LocalDate.now()}","reason":"INV-T-4976 limit case"}"""
        return HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI("http://localhost:$port/internal/v1/policies/VP-72"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer u-op|$tenant|")
                .header("X-Workload-Token", workloadToken("console-bff"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            java.net.http.HttpResponse.BodyHandlers
                .ofString(),
        )
    }

    private fun refusedWhilePlanned(vararg options: String) {
        grantOperator()
        options.forEach { option ->
            val answer = set(option)
            (answer.statusCode() == 200) shouldBe false
            answer.statusCode() shouldBe 422
            answer.body() shouldContain "POL-006"
        }
        limit.limit().days shouldBe 7
    }

    @Test
    fun `INV-AC-2498 a feed silence limit above 30 days is refused and the limit stays 7 days`() =
        refusedWhilePlanned("31", "45", "365", "a longer limit up to 30 days", "31 days")

    @Test
    fun `INV-AC-2513 while VP-72 is planned any limit other than 7 days is refused`() =
        refusedWhilePlanned("8", "14", "30", "DAYS_30", "7 days ")

    @Test
    fun `INV-AC-2404 an air-gapped installation cannot set the pack-silence parameter above 30 days`() {
        refusedWhilePlanned("31", "9999")
        // the installation level is not reachable from a token either: it never carries the platform tenant
        val installation = set("31", level = "INSTALLATION")
        (installation.statusCode() == 200) shouldBe false
        limit.limit().days shouldBe 7
    }
}
