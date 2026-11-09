package com.dynokore.gst.rules.served

import com.dynokore.gst.rules.bootstrap.PostgresRules
import com.dynokore.gst.rules.bootstrap.RulesApplication
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val ROUTE = "/internal/v1/policies/VP-39"
private const val DEFAULT_OPTION = "REVIEW_THEN_ACTIVATE"
private const val SET_OPTION = "ACTIVATE_WITH_NOTICE"
private val VP_39 = VariationPointKey("VP-39")

/**
 * INV-T-18451 (INV-Q-8972 A, INV-Q-10027): the answers of the mounted `POST /internal/v1/policies/VP-39` on real
 * PostgreSQL (the migrations of this module run through MigrationRunner as rules_migrate, the service on rules_runtime)
 * and on a replica fixture. The holder set of `vp-39.set` is the role `tax.approver`; the decision is the shared
 * servlet's, by the permission key through `AuthorityReplica.decide`. A stored value is read back by [PolicyReader].
 */
@SpringBootTest(
    classes = [PolicyFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PolicySettingDecisionsIT(
    @LocalServerPort private val port: Int,
    @Autowired private val replica: AuthorityReplica,
    @Autowired private val clock: MovableClock,
    @Autowired private val reader: PolicyReader,
    @Autowired private val scope: TenantScope,
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

        /** The epoch of the access-svc version counter the fixture feeds; a new epoch forgets the snapshot. */
        private val epoch = AtomicLong(1)
    }

    private val http = HttpClient.newHttpClient()
    private val tenant = TenantId(UUID.randomUUID())
    private val today = LocalDate.now(ZoneOffset.UTC)

    @BeforeEach
    fun freshReplica() {
        ReplicaFallbackDouble.reset()
        replica.tracker.admitIncarnation(epoch.get())
        replica.onSnapshotComplete(0) // the feed is connected and synced; the fixture moves the clock from here
        replica.tracker.onApplied(versions.get())
    }

    /** What differs from a plain TENANT-scope grant of the test tenant; unset means the plain value. */
    private data class Shape(
        val scopeKind: GrantScope = GrantScope.TENANT,
        val scopeRef: String? = null,
        val inTenant: TenantId? = null,
        val validUntil: java.time.Instant? = null,
        val state: RoleGrantState = RoleGrantState.ACTIVE,
        val ref: String? = null,
    )

    private fun grant(
        user: String,
        role: String,
        shape: Shape = Shape(),
    ) {
        val where = shape.inTenant ?: tenant
        replica.accept(
            AuthorityChanged(
                where,
                user,
                versions.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(
                    shape.ref ?: "g-$user-$role",
                    shape.state,
                    RoleGrant(
                        role,
                        shape.scopeKind,
                        shape.scopeRef ?: tenant.toString(),
                        clock.instant().minus(Duration.ofHours(1)),
                        shape.validUntil,
                    ),
                ),
                epoch.get(),
            ),
        )
    }

    private fun post(
        user: String,
        option: String = SET_OPTION,
        entities: String = "",
        inTenant: TenantId = tenant,
        key: UUID = UUID.randomUUID(),
    ): HttpResponse<String> {
        val body =
            """{"scopeLevel":"TENANT","scopeRef":"$inTenant","selected":[{"option":"$option"}],""" +
                """"effectiveDate":"$today","reason":"INV-T-18451 decision case"}"""
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port$ROUTE"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $user|$inTenant|$entities")
                .header("X-Workload-Token", workloadToken("console-bff"))
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** The option [PolicyReader] resolves for VP-39 at TENANT scope of [of] on the request's effective date. */
    private fun stored(of: TenantId = tenant): String =
        scope.bind(of, "policy-it-reader") {
            scope.transactions.inTransaction {
                reader
                    .resolve(VP_39, PolicyScope.tenant(of), today)
                    .selected
                    .single()
                    .key
            }
        }

    private fun refusedSrt004(answer: HttpResponse<String>) {
        answer.statusCode() shouldBe 403
        answer.body() shouldContain "SRT-004"
        stored() shouldBe DEFAULT_OPTION
    }

    @Test
    fun `a holder of tax approver at TENANT scope gets 200 and the value is stored`() {
        grant("u-holder", "tax.approver")
        stored() shouldBe DEFAULT_OPTION
        val answer = post("u-holder")
        answer.statusCode() shouldBe 200
        stored() shouldBe SET_OPTION
    }

    @Test
    fun `INV-AC-2224 a caller holding tax approver on entity E1 only is refused 403 SRT-004 at level TENANT`() {
        grant("u-entity", "tax.approver", Shape(scopeKind = GrantScope.ENTITY, scopeRef = "E1"))
        refusedSrt004(post("u-entity", entities = "E1"))
    }

    @Test
    fun `INV-AC-2224 the look-alike role tax approver2 at the same scope is refused 403 SRT-004`() {
        grant("u-lookalike", "tax.approver2")
        refusedSrt004(post("u-lookalike"))
    }

    @Test
    fun `INV-AC-2224 a caller holding another role of the catalogue is refused 403 SRT-004`() {
        grant("u-other", "tenant.admin")
        refusedSrt004(post("u-other"))
    }

    @Test
    fun `INV-AC-2224 a caller with no grant is refused 403 SRT-004`() {
        refusedSrt004(post("u-nobody"))
    }

    @Test
    fun `INV-AC-2224 a value outside the options from a holder is refused and the stored value is unchanged`() {
        grant("u-outside", "tax.approver")
        val answer = post("u-outside", option = "NOT_AN_OPTION")
        (answer.statusCode() in 400..499) shouldBe true
        answer.statusCode() shouldBe 422
        stored() shouldBe DEFAULT_OPTION
    }

    @Test
    fun `a grant held in another tenant is refused 403 SRT-004`() {
        val elsewhere = TenantId(UUID.randomUUID())
        grant("u-away", "tax.approver", Shape(scopeRef = elsewhere.toString(), inTenant = elsewhere))
        refusedSrt004(post("u-away"))
        stored(elsewhere) shouldBe DEFAULT_OPTION
    }

    @Test
    fun `an expired grant is refused 403 SRT-004`() {
        grant("u-expired", "tax.approver", Shape(validUntil = clock.instant().minus(Duration.ofMinutes(1))))
        refusedSrt004(post("u-expired"))
    }

    @Test
    fun `a removed grant is refused 403 SRT-004`() {
        grant("u-removed", "tax.approver", Shape(ref = "g-removed"))
        grant("u-removed", "tax.approver", Shape(ref = "g-removed", state = RoleGrantState.REMOVED))
        refusedSrt004(post("u-removed"))
    }

    @Test
    fun `a voided grant is refused 403 SRT-004`() {
        grant("u-voided", "tax.approver")
        replica.accept(
            AuthorityChanged(
                tenant,
                "u-voided",
                versions.incrementAndGet(),
                AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED),
                epoch.get(),
            ),
        )
        refusedSrt004(post("u-voided"))
    }

    private fun goStale() {
        replica.tracker.onDisconnected()
        clock.advance(Duration.ofSeconds(30))
    }

    @Test
    fun `a stale replica with the grant held and no fallback answer is DEGRADED and refused 403 SRT-004`() {
        grant("u-stale", "tax.approver")
        goStale()
        refusedSrt004(post("u-stale"))
    }

    @Test
    fun `a stale replica is decided by the fallback and a FALLBACK allow stores the value`() {
        goStale()
        ReplicaFallbackDouble.answer = FallbackResult.Found(AuthorityVerdict(true))
        post("u-fallback").statusCode() shouldBe 200
        ReplicaFallbackDouble.asked.single().action shouldBe "vp-39.set"
        ReplicaFallbackDouble.asked.single().level shouldBe ScopeLevel.TENANT
        stored() shouldBe SET_OPTION
    }

    @Test
    fun `a stale replica whose fallback denies is refused 403 SRT-004`() {
        goStale()
        ReplicaFallbackDouble.answer = FallbackResult.Found(AuthorityVerdict(false))
        refusedSrt004(post("u-denied"))
    }

    @Test
    fun `a replica with no snapshot is decided by the fallback, DEGRADED is refused and a FALLBACK allow stores`() {
        replica.tracker.admitIncarnation(epoch.incrementAndGet()) // a new epoch: nothing synced yet
        refusedSrt004(post("u-cold"))
        ReplicaFallbackDouble.answer = FallbackResult.Found(AuthorityVerdict(true))
        val allowed = post("u-cold")
        allowed.statusCode() shouldBe 200
        stored() shouldBe SET_OPTION
    }

    @Test
    fun `the stored value of one tenant is not read under another tenant and a replayed key is not re-run`() {
        grant("u-iso", "tax.approver")
        val key = UUID.randomUUID()
        val first = post("u-iso", key = key)
        first.statusCode() shouldBe 200
        val replay = post("u-iso", key = key)
        replay.statusCode() shouldBe 200
        replay.body() shouldBe first.body()
        stored() shouldBe SET_OPTION
        stored(TenantId(UUID.randomUUID())) shouldBe DEFAULT_OPTION
        first.body() shouldNotContain "SRT-004"
    }
}
