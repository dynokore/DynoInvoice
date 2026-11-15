package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.ReplicaFallbackDouble
import com.dynokore.gst.rules.served.TENANT_A
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
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val DRY_RUN_KEY = "pack.dry-run"
private const val LOOK_ALIKE_KEY = "regulatory-pack.approve"
private const val CONSOLE = "console-bff"
private const val OTHER_SERVICE = "installer"
private const val WINDOW = """{"firstPeriodRef":"2026-Q3","lastPeriodRef":"2026-Q3","periodCount":1}"""
private const val POLL_MILLIS = 100L
private const val POLL_LIMIT = 100

/** The sample and evaluation fixtures of the dry run, with the real `AuthorityReplica` of [PolicyFixtures]. */
@TestConfiguration(proxyBeanMethods = false)
@Import(MappingAllowingPorts::class)
class DryRunPermissionFixtures {
    @Bean fun fixtureSamples() = FixtureHistoricalSamplePort()

    @Bean fun fixtureEvaluation() = FixtureEvaluation()
}

/**
 * INV-AC-22796 (INV-REQ-957): `dryRun` is gated by the scoped key `pack.dry-run`, for a pack of either kind, decided by
 * the real `AuthorityReplica` on real PostgreSQL; the roles are read from the generated `OperationRoles` map (no role
 * literal here). The deployment form is fixed per context, so the cases live in one base class run for SAAS and PRIVATE.
 * The use case receives a call when the sample is released at the end of its run (`FixtureHistoricalSamplePort`).
 */
@Suppress("AbstractClassCanBeConcreteClass", "TooManyFunctions") // JUnit runs only the two form subclasses
abstract class DryRunPermissionCases(
    port: Int,
    private val form: DeploymentForm,
    private val replica: AuthorityReplica,
    private val clock: MovableClock,
    private val samples: FixtureHistoricalSamplePort,
) : DryRunSupport(port) {
    private val tenant: TenantId = TENANT_A
    private val platformProbe: TenancyProbe =
        TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")

    @BeforeEach
    fun freshReplica() {
        ReplicaFallbackDouble.reset()
        samples.clear()
        replica.tracker.admitIncarnation(epoch.get())
        replica.onSnapshotComplete(0)
        replica.tracker.onApplied(versions.get())
    }

    private fun bearer(user: String) = "Bearer $user|$tenant|"

    private fun grantRole(
        user: String,
        role: String,
    ) {
        replica.accept(
            AuthorityChanged(
                tenant,
                user,
                versions.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(
                    "g-$user-$role",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        role,
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

    private fun newUser(role: String? = null): String {
        val user = "u-" + UUID.randomUUID().toString().take(8)
        if (role != null) grantRole(user, role)
        return user
    }

    /** One ARMED mapping pack of tenant A in force since yesterday, so that a candidate has a pack to be compared with. */
    private fun currentPack() {
        val held = "select count(*) from rules.pack_registry where pack_version like 'cur-22796%'"
        if (value(tenant, held) == 1L) return
        val yesterday =
            java.time.LocalDate
                .now(java.time.ZoneOffset.UTC)
                .minusDays(1)
                .toString()
        value(
            tenant,
            "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                "effective_from, effective_to, min_engine_version, supersedes, registered_by, entity_ref) " +
                "values (?::uuid, ?::uuid, 'MAP-cur-22796', 'MAPPING', 'cur-22796', 'TENANT_MAPPING', 'ARMED', " +
                "?::date, null, '1.0.0', null, 'tenancy-probe', 'E-1') returning id",
            UUID.randomUUID().toString(),
            tenant.toString(),
            yesterday,
        )
    }

    private fun mappingRef(): String {
        currentPack()
        return seedPack("cand-22796-" + UUID.randomUUID().toString().take(8))
    }

    /** A STAGED platform regulatory pack; its ref is its version. */
    private fun regulatoryRef(): String {
        val version = "reg-22796-" + UUID.randomUUID().toString().take(8)
        platformProbe.inTenant(TenantId.PLATFORM) {
            fetchValue(
                "select rules.register_platform_pack(?::uuid, 'SCHEMATRON-pack', 'SCHEMATRON', ?, 'STAGED', " +
                    "'2027-01-01'::date, null, '1.0.0', null)",
                UUID.randomUUID().toString(),
                version,
            )
        }
        return version
    }

    private fun unknownRef() = "nope-22796-" + UUID.randomUUID().toString().take(8)

    private fun call(
        ref: String,
        user: String,
        workload: String = CONSOLE,
    ): HttpResponse<String> = dryRun(ref, WINDOW, bearer(user), workloadToken(workload))

    private fun code(answer: HttpResponse<String>): String? =
        Regex(""""code"\s*:\s*"([^"]*)"""").find(answer.body())?.groupValues?.get(1)

    /** The number of calls the use case has received: one release of the sample per run. */
    private fun useCaseCalls(expected: Int): Int {
        var tries = 0
        while (samples.released.size < expected && tries++ < POLL_LIMIT) Thread.sleep(POLL_MILLIS)
        return samples.released.size
    }

    private fun refusedWith(
        answer: HttpResponse<String>,
        status: Int,
        code: String,
        before: Map<String, List<String>>,
    ) {
        withClue(answer.body()) {
            answer.statusCode() shouldBe status
            code(answer) shouldBe code
        }
        samples.released.size shouldBe 0
        snapshot() shouldBe before
    }

    private fun admitted(role: String) {
        samples.clear()
        val ref = mappingRef()
        val user = newUser(role)
        val answer = call(ref, user)
        withClue("$form $role: ${answer.body()}") { (answer.statusCode() == 200 || answer.statusCode() == 202) shouldBe true }
        useCaseCalls(1) shouldBe 1
    }

    @Test
    fun `INV-AC-22796 each role of the pack dry-run map starts the dry run and the map has exactly three roles`() {
        val roles = OperationRoles.rolesFor(form, DRY_RUN_KEY).keys
        roles.size shouldBe 3
        roles.forEach { admitted(it) }
    }

    @Test
    fun `INV-AC-22796 a role absent from the pack dry-run map and present for regulatory-pack approve is refused 403`() {
        val outside = OperationRoles.rolesFor(form, LOOK_ALIKE_KEY).keys - OperationRoles.rolesFor(form, DRY_RUN_KEY).keys
        check(outside.isNotEmpty()) { "$form: no role holds $LOOK_ALIKE_KEY and not $DRY_RUN_KEY" }
        val ref = mappingRef()
        for (role in outside) {
            val before = snapshot()
            refusedWith(call(ref, newUser(role)), 403, "SRT-004", before)
        }
    }

    @Test
    fun `INV-AC-22796 a caller with no grant is refused 403 SRT-004 for a regulatory pack, a mapping pack and an unknown ref`() {
        val refs = listOf(regulatoryRef(), mappingRef(), unknownRef())
        val before = snapshot()
        val user = newUser()
        val answers = refs.map { call(it, user) }
        answers.forEach { refusedWith(it, 403, "SRT-004", before) }
        answers.map { it.statusCode() to code(it) }.toSet().size shouldBe 1
        answers.forEach { it.body() shouldNotContain user }
        // a caller with the key sees the unknown ref as 404: the 403 above reveals no existence
        val holder = newUser(OperationRoles.rolesFor(form, DRY_RUN_KEY).keys.first())
        val missing = call(unknownRef(), holder)
        withClue(missing.body()) { missing.statusCode() shouldBe 404 }
        missing.body() shouldContain "SRT-005"
    }

    @Test
    fun `INV-AC-22796 a decision that is not LOCAL answers 503 SRT-013 and every table is equal`() {
        val holder = newUser(OperationRoles.rolesFor(form, DRY_RUN_KEY).keys.first())
        val ref = mappingRef()
        val before = snapshot()
        replica.tracker.onDisconnected()
        clock.advance(Duration.ofSeconds(30))
        refusedWith(call(ref, holder), 503, "SRT-013", before)
    }

    @Test
    fun `INV-AC-22796 a token of another service is refused 403 SRT-004 and the use case receives 0 calls`() {
        val holder = newUser(OperationRoles.rolesFor(form, DRY_RUN_KEY).keys.first())
        val ref = mappingRef()
        val before = snapshot()
        refusedWith(call(ref, holder, OTHER_SERVICE), 403, "SRT-004", before)
    }

    companion object {
        val versions = AtomicLong(0)
        val epoch = AtomicLong(1)

        fun register(
            registry: DynamicPropertyRegistry,
            form: String,
        ) {
            registerCatalogue(registry)
            registry.add("service-runtime.modules.schema") { "rules" }
            registry.add("service-runtime.idempotency.schema") { "rules" }
            registry.add("rules.dry-run.sample-wait") { "PT2S" }
            registry.add("inv.tenancy.reference-tables") { "rules.pack_registry,rules.pack_entry_source" }
            listOf(CONSOLE, OTHER_SERVICE).forEachIndexed { i, service ->
                registry.add("service-runtime.workload-identity.callers[$i].service") { service }
                registry.add("service-runtime.workload-identity.callers[$i].subject") { subjectOf(service) }
            }
            registry.add("gst.form") { form }
        }
    }
}

@SpringBootTest(
    classes = [PolicyFixtures::class, DryRunPermissionFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DryRunPermissionSaasTest
    @Autowired
    constructor(
        @LocalServerPort port: Int,
        replica: AuthorityReplica,
        clock: MovableClock,
        samples: FixtureHistoricalSamplePort,
    ) : DryRunPermissionCases(port, DeploymentForm.SAAS, replica, clock, samples) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) = register(registry, "saas")
        }
    }

@SpringBootTest(
    classes = [PolicyFixtures::class, DryRunPermissionFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DryRunPermissionPrivateTest
    @Autowired
    constructor(
        @LocalServerPort port: Int,
        replica: AuthorityReplica,
        clock: MovableClock,
        samples: FixtureHistoricalSamplePort,
    ) : DryRunPermissionCases(port, DeploymentForm.PRIVATE, replica, clock, samples) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) = register(registry, "private")
        }
    }
