package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.web.PacksController
import com.dynokore.gst.rules.application.ApproveRegulatoryPack
import com.dynokore.gst.rules.domain.pack.ArmingGate
import com.dynokore.gst.rules.domain.pack.PackStatus
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
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val STAGER = "tenancy-probe"
private const val APPROVER = "u-approver"
private const val APPROVE_ROLE = "regulatory.approver"
private const val PACK_TYPE = "SCHEMATRON"

/**
 * INV-T-4975 (INV-REQ-489): the served `approveRegulatoryPack` over the wired context on real PostgreSQL and a replica
 * fixture. The permission is the scoped key `regulatory-pack.approve` decided by `AuthorityReplica`, the approver is
 * the authenticated user ID, and the activation fence is not built yet (INV-T-4977): the first two requests of
 * INV-AC-7966 are asserted through [ArmingGate] on the stored status and delta.
 */
@SpringBootTest(
    classes = [PolicyFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Suppress("TooManyFunctions") // one case per refusal of the contract
class RegulatoryPackApprovalBoundaryTest(
    @LocalServerPort private val port: Int,
    @Autowired private val replica: AuthorityReplica,
    @Autowired private val clock: MovableClock,
    @Autowired private val context: ApplicationContext,
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
        }

        private val versions = AtomicLong(0)
        private val epoch = AtomicLong(1)
    }

    private val probe: TenancyProbe =
        TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    private val http = HttpClient.newHttpClient()
    private val tenant = TenantId(UUID.randomUUID())

    @BeforeEach
    fun freshReplica() {
        ReplicaFallbackDouble.reset()
        replica.tracker.admitIncarnation(epoch.get())
        replica.onSnapshotComplete(0)
        replica.tracker.onApplied(versions.get())
    }

    private fun grantApprover(user: String) {
        replica.accept(
            AuthorityChanged(
                tenant,
                user,
                versions.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(
                    "g-$user",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        APPROVE_ROLE,
                        GrantScope.TENANT,
                        tenant.toString(),
                        clock.instant().minus(Duration.ofHours(1)),
                        null,
                    ),
                ),
                epoch.get(),
            ),
        )
    }

    private fun platform(
        sql: String,
        vararg binds: Any?,
    ): Any? = probe.inTenant(TenantId.PLATFORM) { fetchValue(sql, *binds) }

    /** A platform SCHEMATRON version in [status], registered by [STAGER] (the probe's bound actor). */
    private fun seed(status: String = "STAGED"): String {
        val version = "t" + UUID.randomUUID().toString().take(8)
        platform(
            "select rules.register_platform_pack(?::uuid, 'SCHEMATRON-pack', ?, ?, ?, '2027-01-01'::date, null, " +
                "'1.0.0', null)",
            UUID.randomUUID().toString(),
            PACK_TYPE,
            version,
            status,
        )
        return version
    }

    private fun addDryRun(version: String) {
        platform(
            "insert into rules.dry_run_delta (id, tenant_id, pack_type, pack_version, state, started_by, " +
                "completed_at, changed_count) values (?::uuid, ?::uuid, ?, ?, 'COMPLETE', 'u-dry', " +
                "clock_timestamp(), 0) returning id",
            UUID.randomUUID().toString(),
            TenantId.PLATFORM.toString(),
            PACK_TYPE,
            version,
        )
    }

    private fun status(version: String) =
        platform(
            "select status from rules.pack_registry where pack_type = ? and pack_version = ?",
            PACK_TYPE,
            version,
        ) as String

    private fun approvedBy(version: String) =
        platform(
            "select approved_by from rules.pack_registry where pack_type = ? and pack_version = ?",
            PACK_TYPE,
            version,
        ) as String?

    private fun hasDryRun(version: String) =
        platform(
            "select count(*) from rules.dry_run_delta where pack_type = ? and pack_version = ? and state = 'COMPLETE'",
            PACK_TYPE,
            version,
        ) as Long > 0

    private fun approve(
        user: String,
        version: String,
        type: String = PACK_TYPE,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/internal/v1/rules/regulatory-packs/$type/$version/approval"))
                .header("Authorization", "Bearer $user|$tenant|")
                .header("X-Workload-Token", workloadToken("console-bff"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun gate(version: String) = ArmingGate.check(PackStatus.valueOf(status(version)), hasDryRun(version))

    private fun refused(
        answer: HttpResponse<String>,
        http: Int,
        code: String,
    ) {
        answer.statusCode() shouldBe http
        answer.body() shouldContain code
    }

    @Test
    fun `INV-AC-7966 activation before any dry run and before approval is refused, then the tax approver approves`() {
        val version = seed()
        val controller = context.getBean(PacksController::class.java)
        val usecase = context.getBean(ApproveRegulatoryPack::class.java)
        val startup = context.startupDate

        // 1. activation before any dry run: refused through the pack lifecycle, no error code, nothing changes
        gate(version) shouldBe ArmingGate.Outcome.Refused(ArmingGate.Reason.NO_DRY_RUN)
        status(version) shouldBe "STAGED"

        // 2. after the dry run and before approval
        addDryRun(version)
        gate(version) shouldBe ArmingGate.Outcome.Refused(ArmingGate.Reason.NOT_APPROVED)
        status(version) shouldBe "STAGED"

        // 3. the approval by a caller whose grants include the scoped permission: APPROVED, still not active
        grantApprover(APPROVER)
        val answer = approve(APPROVER, version)
        answer.statusCode() shouldBe 200
        answer.body() shouldContain "\"APPROVED\""
        status(version) shouldBe "APPROVED"
        approvedBy(version) shouldBe APPROVER
        (status(version) == "ARMED") shouldBe false

        // the application context object is unchanged throughout
        (context.getBean(PacksController::class.java) === controller) shouldBe true
        (context.getBean(ApproveRegulatoryPack::class.java) === usecase) shouldBe true
        context.startupDate shouldBe startup
    }

    @Test
    fun `INV-AC-536 a new pack version passes the dry run and the approval and no service image or context changes`() {
        val version = seed()
        addDryRun(version)
        val before = context.getBeanDefinitionNames().toList()
        grantApprover(APPROVER)
        approve(APPROVER, version).statusCode() shouldBe 200
        status(version) shouldBe "APPROVED"
        context.getBeanDefinitionNames().toList() shouldBe before
    }

    @Test
    fun `INV-AC-7966 the approval by a user whose ID differs from the staging user is recorded under that ID`() {
        val version = seed()
        addDryRun(version)
        grantApprover("u-second")
        approve("u-second", version).statusCode() shouldBe 200
        approvedBy(version) shouldBe "u-second"
    }

    @Test
    fun `INV-AC-7966 the approval by the user who staged the pack is refused 403 SRT-004 and nothing changes`() {
        val version = seed()
        addDryRun(version)
        grantApprover(STAGER)
        val answer = approve(STAGER, version)
        refused(answer, 403, "SRT-004")
        answer.body() shouldNotContain STAGER
        status(version) shouldBe "STAGED"
        approvedBy(version) shouldBe null
    }

    @Test
    fun `INV-AC-7966 a caller without the permission is refused 403 SRT-004 with no person in the detail`() {
        val version = seed()
        addDryRun(version)
        val answer = approve("u-nobody", version)
        refused(answer, 403, "SRT-004")
        answer.body() shouldNotContain "u-nobody"
        status(version) shouldBe "STAGED"
    }

    @Test
    fun `INV-AC-7966 an unknown pack version is 404 SRT-005`() {
        grantApprover(APPROVER)
        refused(approve(APPROVER, "nope" + UUID.randomUUID().toString().take(6)), 404, "SRT-005")
    }

    @Test
    fun `INV-AC-7966 a pack with no dry run is 409 SRT-008 and stays STAGED`() {
        val version = seed()
        grantApprover(APPROVER)
        refused(approve(APPROVER, version), 409, "SRT-008")
        status(version) shouldBe "STAGED"
    }

    @Test
    fun `INV-AC-7966 a pack that is not STAGED is 409 SRT-008 and an ARMED pack is not approved`() {
        val version = seed("ARMED")
        addDryRun(version)
        grantApprover(APPROVER)
        refused(approve(APPROVER, version), 409, "SRT-008")
        status(version) shouldBe "ARMED"
    }

    @Test
    fun `INV-AC-7966 a repeat of the approval answers 200 and keeps the first approver`() {
        val version = seed()
        addDryRun(version)
        grantApprover(APPROVER)
        grantApprover("u-third")
        approve(APPROVER, version).statusCode() shouldBe 200
        approve("u-third", version).statusCode() shouldBe 200
        approvedBy(version) shouldBe APPROVER
    }

    @Test
    fun `INV-AC-7966 an unavailable permission decision is 503 SRT-013 and nothing is changed`() {
        val version = seed()
        addDryRun(version)
        grantApprover(APPROVER)
        replica.tracker.onDisconnected()
        clock.advance(Duration.ofSeconds(30))
        refused(approve(APPROVER, version), 503, "SRT-013")
        status(version) shouldBe "STAGED"
        approvedBy(version) shouldBe null
    }
}
