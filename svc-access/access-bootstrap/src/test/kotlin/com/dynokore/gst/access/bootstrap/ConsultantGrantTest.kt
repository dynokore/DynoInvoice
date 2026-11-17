package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.application.consultant.ConsultantDecisionOutcome
import com.dynokore.gst.access.application.consultant.DecideConsultantGrantUseCase
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import com.dynokore.gst.access.contract.model.ConsultantGrantDecisionRequest
import com.dynokore.gst.access.contract.model.ConsultantGrantRequest
import com.dynokore.gst.access.contract.model.ConsultantGrantRequestResponse
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-T-15800 (INV-REQ-908, INV-REQ-2465; INV-Q-15786, INV-Q-15794 A): `requestConsultantGrant` and
 * `decideConsultantGrant` at the service boundary over a real PostgreSQL, the real permission matrix of the pinned
 * gst-catalogue and the real variation point catalogue (VP-69 `planned`). The callers hold keys
 * (`consultant-grant.request`
 * on a platform grant, `user.manage` on a grant of the tenant); no role name is compared.
 */
class ConsultantGrantTest {
    private val platform = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    private val run = UUID.randomUUID().toString().take(8)

    private fun user(name: String) = "user:$name-$run"

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val a = TenantId(UUID.randomUUID())
        val b = TenantId(UUID.randomUUID())
        val tenants = context.getBean(TenantContext::class.java)
        val api = context.getBean(GrantsController::class.java)
        val authorise = context.getBean(AuthoriseController::class.java)
        val clock = context.getBean(Clock::class.java) as MovableClock
        val support = user("support")
        val operator = user("operator")
        val admin = user("admin")
        val adminB = user("admin-b")
        val both = user("both")
        val consultant = user("consultant")

        init {
            platformRow(support, "platform.support")
            platformRow(operator, "deployment.operator")
            platformRow(both, "platform.support")
            Seeds.grant(context, a, SeedSpec(admin, Role.TENANT_ADMIN))
            Seeds.grant(context, a, SeedSpec(both, Role.TENANT_ADMIN))
            Seeds.grant(context, b, SeedSpec(adminB, Role.TENANT_ADMIN))
            val replica = context.getBean(StructureReplica::class.java)
            replica.accept(
                OrganisationStructureChanged(a, "ENT-A", StructureChange.CREATED, BoundaryFixture.entityNode()),
            )
        }

        private fun platformRow(
            identity: String,
            role: String,
        ) = BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute(
                    "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                        "updated_at, updated_by, version) select gen_random_uuid(), '$platform', '$identity', " +
                        "'STAFF', now(), 'mig', now(), 'mig', 0 where not exists (select 1 from access.identity " +
                        "where tenant_id = '$platform' and identity_ref = '$identity')",
                )
                it.execute(
                    "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                        "scope_ref, valid_from, valid_until, status, grant_source, created_at, created_by, " +
                        "updated_at, updated_by, version) values (gen_random_uuid(), '$platform', " +
                        "'GRT-${UUID.randomUUID().toString().take(8)}', '$identity', '$role', 'TENANT', '$platform', " +
                        "now() - interval '1 day', now() + interval '3000 days', 'ACTIVE', 'BY_HAND', now(), 'mig', " +
                        "now(), 'mig', 0)",
                )
            }
        }

        fun removePlatformRows() =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use {
                    val own = "tenant_id = '$platform' and identity_ref like '%-$run'"
                    it.execute("delete from access.role_grant where $own")
                    it.execute("delete from access.identity where $own")
                }
            }

        fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

        fun goLive(): LocalDate = today().plusDays(10)

        fun <T> into(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = tenants.bind(tenant, actor) { block() }

        fun request(
            actor: String = support,
            tenant: TenantId = a,
            tenantRef: String = tenant.toString(),
            identity: String = consultant,
            goLive: LocalDate = goLive(),
        ): ConsultantGrantRequestResponse =
            into(tenant, actor) {
                api
                    .requestConsultantGrant(
                        UUID.randomUUID(),
                        ConsultantGrantRequest(tenantRef, identity, goLive),
                    ).body!!
            }

        fun decide(
            ref: String,
            actor: String = admin,
            tenant: TenantId = a,
            approve: Boolean = true,
            days: Int? = null,
        ): ConsultantGrantRequestResponse =
            into(tenant, actor) {
                api
                    .decideConsultantGrant(
                        ref,
                        UUID.randomUUID(),
                        ConsultantGrantDecisionRequest(
                            if (approve) {
                                ConsultantGrantDecisionRequest.Decision.APPROVE
                            } else {
                                ConsultantGrantDecisionRequest.Decision.DENY
                            },
                            days,
                        ),
                    ).body!!
            }

        fun decision(
            actor: String,
            permission: String,
        ) = into(a, actor) { authorise.authorise(actor, permission, "entity:ENT-A").body!!.decision }

        fun <T> sql(
            query: String,
            map: (java.sql.ResultSet) -> T,
        ): List<T> =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { s ->
                    s.executeQuery(query).use { r -> generateSequence { if (r.next()) map(r) else null }.toList() }
                }
            }

        fun requestRows(tenant: TenantId = a) =
            sql(
                "select count(*) from access.consultant_grant_request where tenant_id = '$tenant'",
            ) { it.getInt(1) }.single()

        fun consultantGrants() =
            sql(
                "select grant_ref, scope_level, scope_ref, valid_from::date, valid_until::date " +
                    "from access.role_grant where tenant_id = '$a' and identity_ref = '$consultant' " +
                    "and role = '${Role.IMPLEMENTATION_CONSULTANT.wire}'",
            ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) }

        fun granteeKind(identity: String): List<String> =
            sql(
                "select grantee_kind from access.identity where tenant_id = '$a' and identity_ref = '$identity'",
            ) { it.getString(1) }

        fun seedStaffIdentity(identity: String) =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use {
                    it.execute(
                        "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, " +
                            "created_by, updated_at, updated_by, version) values (gen_random_uuid(), '$a', " +
                            "'$identity', 'STAFF', now(), 'mig', now(), 'mig', 0)",
                    )
                }
            }

        fun statusOf(ref: String): String =
            sql(
                "select status from access.consultant_grant_request where request_ref = '$ref'",
            ) { it.getString(1) }.single()

        fun authorityChanged() =
            sql(
                "select count(*) from access.outbox where tenant_id = '$a' and type = " +
                    "'${PlatformEventCatalogue.AUTHORITY_CHANGED.eventType}'",
            ) { it.getInt(1) }.single()

        /** (actor, action) of every audit entry of tenant a, in order. */
        fun audit(): List<Pair<String, String>> =
            sql(
                "select data::text from access.outbox where tenant_id = '$a' " +
                    "and type like '%AuditEntryRecorded%' order by seq",
            ) { it.getString(1) }.map {
                Regex("\"actor\":\\s*\"([^\"]+)\"").find(it)!!.groupValues[1] to
                    Regex("\"action\":\\s*\"([^\"]+)\"").find(it)!!.groupValues[1]
            }
    }

    private fun run(
        form: String = "SAAS",
        available: Boolean = false,
        block: (Env) -> Unit,
    ) {
        BoundaryFixture
            .runner(catalogueMatrix = true, form = form, requirements = null)
            .let {
                if (available) {
                    it.withBean(
                        VariationPointCatalogue::class.java,
                        { AvailableVp69(GstVariationPointCatalogue("access-svc")) },
                    )
                } else {
                    it
                }
            }.run { context ->
                context.startupFailure?.let { throw it }
                val env = Env(context)
                try {
                    block(env)
                } finally {
                    env.removePlatformRows()
                }
            }
    }

    private fun refusal(block: () -> Any?): ApiRefusal {
        val thrown = catchThrowable { block() }
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        return thrown as ApiRefusal
    }

    private fun status(refusal: ApiRefusal) = Problems.render(refusal.code, "corr", refusal.detail, null).status

    /** 400: bean validation enforces the contract bounds (1..120) first; the use case enforces the rest. */
    private fun badRequest(block: () -> Any?) {
        val thrown = catchThrowable { block() }
        if (thrown?.javaClass?.simpleName == "ConstraintViolationException") return
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        assertThat(status(thrown as ApiRefusal)).isEqualTo(400)
    }

    private fun denied(block: () -> Any?) {
        val refused = refusal(block)
        assertThat(refused.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        assertThat(status(refused)).isEqualTo(403)
    }

    // ---- INV-AC-1942 ---------------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-1942 an approved consultant grant covers set-up only, masks content and ends 30 days after go-live`() =
        run { env ->
            val requested = env.request()
            assertThat(requested.status).isEqualTo(ConsultantGrantRequestResponse.Status.PENDING)
            assertThat(requested.tenantRef).isEqualTo(env.a.toString())
            assertThat(requested.identityRef).isEqualTo(env.consultant)
            assertThat(requested.goLiveDate).isEqualTo(env.goLive())
            val before = env.authorityChanged()
            val approved = env.decide(requested.requestRef)
            assertThat(approved.status).isEqualTo(ConsultantGrantRequestResponse.Status.APPROVED)
            assertThat(approved.validTo).isEqualTo(env.goLive().plusDays(30))
            val grants = env.consultantGrants()
            assertThat(grants).hasSize(1)
            assertThat(grants.single()[0]).isEqualTo(approved.grantRef)
            assertThat(grants.single().subList(1, 5))
                .containsExactly("TENANT", env.a.toString(), env.today().toString(), approved.validTo.toString())
            // AuthorityChanged is emitted by the writer of grantRole, in the same transaction
            assertThat(env.authorityChanged()).isGreaterThan(before)
            // set-up, integration and mapping drafts only; document content is masked without elevation
            listOf(
                "onboarding.entity-setup.configure",
                "onboarding.integration.configure",
                "onboarding.mapping-draft.configure",
            ).forEach {
                assertThat(env.decision(env.consultant, it)).describedAs(it).isEqualTo(AuthoriseResponse.Decision.ALLOW)
            }
            assertThat(env.decision(env.consultant, "doc.view.content")).isEqualTo(AuthoriseResponse.Decision.DENY)
            // 30 days after go-live the grant has expired
            env.clock.advance(Duration.ofDays(10 + 30 + 1))
            assertThat(env.decision(env.consultant, "onboarding.entity-setup.configure"))
                .isEqualTo(AuthoriseResponse.Decision.DENY)
        }

    @Test
    fun `INV-AC-1942 each request and decision writes an audit entry whose actor is the acting user ID`() =
        run { env ->
            val ref = env.request().requestRef
            env.decide(ref)
            val consultantAudit = env.audit().filter { it.second.endsWith("ConsultantGrant") }
            assertThat(consultantAudit)
                .containsExactly(env.support to "requestConsultantGrant", env.admin to "decideConsultantGrant")
            val roles = Role.entries.map { it.wire }.toSet()
            assertThat(env.audit().map { it.first }).noneMatch { it in roles }
        }

    @Test
    fun `INV-AC-1942 a deny writes no grant, and a decision on a decided request is 409`() =
        run { env ->
            val ref = env.request().requestRef
            val denied = env.decide(ref, approve = false)
            assertThat(denied.status).isEqualTo(ConsultantGrantRequestResponse.Status.DENIED)
            assertThat(denied.grantRef).isNull()
            assertThat(env.consultantGrants()).isEmpty()
            listOf(true, false).forEach {
                assertThat(catchThrowable { env.decide(ref, approve = it) }).isInstanceOf(ApiConflict::class.java)
            }
            assertThat(env.statusOf(ref)).isEqualTo("DENIED")
            val approvedRef = env.request(goLive = env.goLive().plusDays(1)).requestRef
            env.decide(approvedRef)
            assertThat(catchThrowable { env.decide(approvedRef) }).isInstanceOf(ApiConflict::class.java)
            assertThat(env.consultantGrants()).hasSize(1)
        }

    @Test
    fun `INV-AC-1942 a request for another tenant than the call's is refused and nothing is stored`() =
        run { env ->
            denied { env.request(tenantRef = env.b.toString()) }
            assertThat(env.requestRows()).isZero()
            assertThat(env.requestRows(env.b)).isZero()
        }

    @Test
    fun `INV-AC-1942 the approver equal to the requester is refused and the request stays pending`() =
        run { env ->
            val ref = env.request(actor = env.both).requestRef
            denied { env.decide(ref, actor = env.both) }
            assertThat(env.statusOf(ref)).isEqualTo("PENDING")
            assertThat(env.consultantGrants()).isEmpty()
            // control: another approver succeeds
            assertThat(env.decide(ref).status).isEqualTo(ConsultantGrantRequestResponse.Status.APPROVED)
        }

    @Test
    fun `INV-AC-1942 a grant that would not end after the approval date is 400 and writes nothing`() =
        run { env ->
            val ref = env.request(goLive = env.today().minusDays(30)).requestRef
            badRequest { env.decide(ref) }
            assertThat(env.statusOf(ref)).isEqualTo("PENDING")
            assertThat(env.consultantGrants()).isEmpty()
            val ref2 = env.request(goLive = env.today().minusDays(29)).requestRef
            assertThat(env.decide(ref2).validTo).isEqualTo(env.today().plusDays(1))
        }

    @Test
    fun `INV-AC-1942 a caller with user manage but without the request key is refused on the request`() =
        run { env ->
            denied { env.request(actor = env.admin) }
            assertThat(env.requestRows()).isZero()
            // control: the same call by a holder of the key succeeds
            assertThat(env.request(actor = env.support).status).isEqualTo(ConsultantGrantRequestResponse.Status.PENDING)
            assertThat(env.requestRows()).isEqualTo(1)
        }

    @Test
    fun `INV-AC-1942 a caller with the request key but without user manage is refused on the decision`() =
        run { env ->
            val ref = env.request().requestRef
            denied { env.decide(ref, actor = env.support) }
            assertThat(env.statusOf(ref)).isEqualTo("PENDING")
            assertThat(env.consultantGrants()).isEmpty()
            assertThat(env.decide(ref, actor = env.admin).status)
                .isEqualTo(ConsultantGrantRequestResponse.Status.APPROVED)
        }

    @Test
    fun `INV-AC-1942 deployment operator holds the key in the private form only`() {
        run("PRIVATE") { env ->
            assertThat(env.request(actor = env.operator).status)
                .isEqualTo(ConsultantGrantRequestResponse.Status.PENDING)
        }
        run("SAAS") { env ->
            denied { env.request(actor = env.operator) }
            assertThat(env.requestRows()).isZero()
        }
    }

    @Test
    fun `INV-AC-1942 a repeat of the same request while pending returns the stored one`() =
        run { env ->
            val first = env.request()
            val again = env.request()
            assertThat(again.requestRef).isEqualTo(first.requestRef)
            assertThat(env.requestRows()).isEqualTo(1)
        }

    @Test
    fun `INV-AC-1942 an approved grant stores a new identity as EXTERNAL and leaves an existing STAFF row STAFF`() =
        run { env ->
            val fresh = env.request()
            assertThat(env.granteeKind(env.consultant)).isEmpty()
            env.decide(fresh.requestRef)
            assertThat(env.granteeKind(env.consultant)).containsExactly("EXTERNAL")
            val staff = user("staff-consultant")
            env.seedStaffIdentity(staff)
            env.decide(env.request(identity = staff).requestRef)
            assertThat(env.granteeKind(staff)).containsExactly("STAFF")
        }

    // ---- INV-AC-2494 ---------------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-2494 121 days is refused with VP-69 available, 120 succeeds`() =
        run(available = true) { env ->
            val ref = env.request().requestRef
            badRequest { env.decide(ref, days = 121) }
            // the use case refuses it too, with no bean validation in front
            val useCase = env.context.getBean(DecideConsultantGrantUseCase::class.java)
            val direct =
                env.into(env.a, env.admin) {
                    useCase.handle(IdentityRef(env.admin), ref, true, 121)
                }
            assertThat(direct).isEqualTo(ConsultantDecisionOutcome.PeriodOutOfRange)
            assertThat(env.statusOf(ref)).isEqualTo("PENDING")
            assertThat(env.consultantGrants()).isEmpty()
            val approved = env.decide(ref, days = 120)
            assertThat(approved.validTo).isEqualTo(env.goLive().plusDays(120))
        }

    @Test
    fun `INV-AC-2494 a period below one day or above the maximum is refused whatever VP-69 says`() {
        listOf(false, true).forEach { available ->
            run(available = available) { env ->
                val ref = env.request().requestRef
                listOf(0, -1, 121, 1000).forEach { badRequest { env.decide(ref, days = it) } }
                assertThat(env.consultantGrants()).isEmpty()
            }
        }
    }

    // ---- INV-AC-2512 ---------------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-2512 while VP-69 is planned 120, 60 and 31 days are refused and 30 or none is approved`() =
        run { env ->
            val ref = env.request().requestRef
            listOf(120, 60, 31, 29, 1).forEach { badRequest { env.decide(ref, days = it) } }
            assertThat(env.consultantGrants()).isEmpty()
            assertThat(env.statusOf(ref)).isEqualTo("PENDING")
            assertThat(env.decide(ref, days = 30).validTo).isEqualTo(env.goLive().plusDays(30))
            val ref2 = env.request(goLive = env.goLive().plusDays(1)).requestRef
            assertThat(env.decide(ref2, days = null).validTo).isEqualTo(env.goLive().plusDays(31))
        }

    @Test
    fun `INV-AC-2512 the pinned catalogue holds VP-69 as planned`() {
        val pinned = GstVariationPointCatalogue("access-svc").find(VariationPointKey("VP-69"))
        assertThat(pinned!!.status).isEqualTo(VpStatus.PLANNED)
    }
}
