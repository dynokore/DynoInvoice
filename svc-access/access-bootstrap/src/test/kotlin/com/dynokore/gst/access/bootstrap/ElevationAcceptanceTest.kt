package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.elevation.ElevationController
import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.application.elevation.DecideElevationUseCase
import com.dynokore.gst.access.application.elevation.ElevationKeys
import com.dynokore.gst.access.application.elevation.ElevationOutcome
import com.dynokore.gst.access.application.elevation.OpenElevationUseCase
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import com.dynokore.gst.access.contract.model.DecideElevationRequest
import com.dynokore.gst.access.contract.model.DiagnosticInvitationRequest
import com.dynokore.gst.access.contract.model.DiagnosticInvitationResponse
import com.dynokore.gst.access.contract.model.ElevationRequest
import com.dynokore.gst.access.contract.model.ElevationResponse
import com.dynokore.gst.access.domain.elevation.ElevationAsk
import com.dynokore.gst.access.domain.elevation.ElevationKind
import com.dynokore.gst.access.domain.elevation.OperatorAccess
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
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
import java.util.UUID

/**
 * INV-T-4941 (INV-REQ-862, INV-REQ-888, INV-REQ-890, INV-REQ-1268; INV-Q-15950 A, INV-Q-15952 A, INV-Q-15954 A): the
 * elevation, the diagnostic invitation and the two withdrawals, at the service boundary against the real catalogue
 * matrix. Only the part each criterion assigns to this service is asserted: the decision of `authorise` and the
 * refusals; masking of a document is the document service's.
 */
class ElevationAcceptanceTest {
    private val platform = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    private val run = UUID.randomUUID().toString().take(8)
    private val content = "doc.view.content"

    private fun user(name: String) = "user:$name-$run"

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val a = TenantId(UUID.randomUUID())
        val b = TenantId(UUID.randomUUID())
        val tenants = context.getBean(TenantContext::class.java)
        val api = context.getBean(ElevationController::class.java)
        val authorise = context.getBean(AuthoriseController::class.java)
        val clock = context.getBean(Clock::class.java) as MovableClock
        val support = user("support")
        val operator = user("operator")
        val officer = user("officer")
        val officerB = user("officer-b")
        val admin = user("admin")

        init {
            platformRow(support, "platform.support")
            platformRow(operator, "deployment.operator")
            Seeds.grant(context, a, SeedSpec(officer, Role.SECURITY_OFFICER))
            Seeds.grant(context, a, SeedSpec(admin, Role.TENANT_ADMIN))
            Seeds.grant(context, a, SeedSpec(operator, Role.DEPLOYMENT_OPERATOR))
            Seeds.grant(context, b, SeedSpec(operator, Role.DEPLOYMENT_OPERATOR))
            Seeds.grant(context, b, SeedSpec(officerB, Role.SECURITY_OFFICER))
            val replica = context.getBean(StructureReplica::class.java)
            listOf(a to "ENT-A", b to "ENT-B").forEach { (tenant, entity) ->
                replica.accept(
                    OrganisationStructureChanged(tenant, entity, StructureChange.CREATED, BoundaryFixture.entityNode()),
                )
            }
        }

        /** The platform tenant is bound only through its own binding, so its grants are seeded as the superuser. */
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
                        "now() - interval '1 day', now() + interval '30 days', 'ACTIVE', 'BY_HAND', now(), 'mig', " +
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

        fun <T> into(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = tenants.bind(tenant, actor) { block() }

        fun request(
            actor: String = support,
            tenant: TenantId = a,
            case: String = "CASE-1",
        ): ElevationResponse =
            into(tenant, actor) {
                api.requestElevation(UUID.randomUUID(), ElevationRequest(case, tenant.toString(), "investigate")).body!!
            }

        fun invite(
            actor: String = operator,
            tenant: TenantId = a,
            case: String = "CASE-1",
        ): DiagnosticInvitationResponse =
            into(tenant, actor) {
                api.inviteDiagnostics(UUID.randomUUID(), DiagnosticInvitationRequest("INST-1", case)).body!!
            }

        @Suppress("LongParameterList") // test helper with defaults
        fun decide(
            ref: String,
            actor: String = officer,
            tenant: TenantId = a,
            grant: Boolean = true,
            minutes: Int = 30,
            key: UUID = UUID.randomUUID(),
        ) = into(tenant, actor) {
            api.decideElevation(
                ref,
                key,
                DecideElevationRequest(
                    if (grant) DecideElevationRequest.Decision.GRANT else DecideElevationRequest.Decision.REFUSE,
                    if (grant) minutes else null,
                ),
            )
        }

        /** The operator's platform grant ends (revoked), as the grant lifecycle would do it. */
        fun revokePlatformGrant(identity: String) =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use {
                    it.execute(
                        "update access.role_grant set status = 'REVOKED' " +
                            "where tenant_id = '$platform' and identity_ref = '$identity'",
                    )
                }
            }

        fun withdrawElevation(
            ref: String,
            actor: String = support,
        ) = into(a, actor) { api.withdrawElevationRequest(ref, UUID.randomUUID()) }

        fun withdrawInvitation(
            ref: String,
            actor: String = operator,
        ) = into(a, actor) { api.withdrawDiagnosticInvitation(ref, UUID.randomUUID()) }

        fun ask(
            actor: String,
            permission: String,
            entity: String = "ENT-A",
            tenant: TenantId = a,
        ): AuthoriseResponse = into(tenant, actor) { authorise.authorise(actor, permission, "entity:$entity").body!! }

        fun decision(
            actor: String,
            permission: String,
            entity: String = "ENT-A",
            tenant: TenantId = a,
        ) = ask(actor, permission, entity, tenant).decision

        fun auditActions(tenant: TenantId = a): List<String> =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { s ->
                    s
                        .executeQuery(
                            "select data::text from access.outbox where tenant_id = '$tenant' " +
                                "and type like '%AuditEntryRecorded%' order by seq",
                        ).use { r ->
                            generateSequence { if (r.next()) r.getString(1) else null }
                                .map {
                                    Regex("\"action\":\\s*\"([^\"]+)\"")
                                        .find(it)
                                        ?.groupValues
                                        ?.get(1)
                                        .orEmpty()
                                }.toList()
                        }
                }
            }

        fun rows(): String =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { s ->
                    s.executeQuery("select * from access.elevation order by id").use { r ->
                        val n = r.metaData.columnCount
                        val out = StringBuilder()
                        while (r.next()) (1..n).forEach { out.append(r.getString(it)).append(',') }
                        out.toString()
                    }
                }
            }
    }

    private fun run(
        form: String,
        block: (Env) -> Unit,
    ) {
        BoundaryFixture.runner(catalogueMatrix = true, form = form, requirements = null).run { context ->
            context.startupFailure?.let { throw it }
            val env = Env(context)
            try {
                block(env)
            } finally {
                env.removePlatformRows()
            }
        }
    }

    private fun refusal(block: () -> Any?): ClosedErrorCode {
        val thrown = catchThrowable { block() }
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        return (thrown as ApiRefusal).code
    }

    private val allow = AuthoriseResponse.Decision.ALLOW
    private val deny = AuthoriseResponse.Decision.DENY

    @Test
    fun `INV-AC-922 platform support holds no invoice content by default, the matrix lists no such cell`() =
        run("SAAS") { env ->
            val matrix = env.context.getBean(PermissionMatrix::class.java)
            assertThat(matrix.export().getValue("platform.support")).doesNotContainKey(content)
            assertThat(env.decision(env.support, content)).isEqualTo(deny)
            assertThat(env.ask(env.support, content).reasonCode).isNotBlank()
        }

    @Test
    fun `INV-AC-1930 an elevation waits for the officer, shows no content before approval, and is audited`() =
        run("SAAS") { env ->
            val pending = env.request()
            assertThat(pending.status).isEqualTo(ElevationResponse.Status.PENDING)
            assertThat(pending.requestRef).startsWith("ELV-")
            assertThat(env.decision(env.support, content)).isEqualTo(deny)
            env.decide(pending.requestRef)
            assertThat(env.decision(env.support, content)).isEqualTo(allow)
            assertThat(env.auditActions()).contains("requestElevation", "approveElevation", "elevatedAccess")
        }

    @Test
    fun `INV-AC-7953 the first decision equals DENY and the second ALLOW after the officer approves`() =
        run("SAAS") { env ->
            val ref = env.request().requestRef
            val first = env.decision(env.support, content)
            env.decide(ref)
            val second = env.decision(env.support, content)
            assertThat(listOf(first, second)).containsExactly(deny, allow)
            assertThat(env.ask(env.support, content).role).isEqualTo("platform.support")
            assertThat(env.auditActions()).contains("approveElevation", "elevatedAccess")
        }

    @Test
    fun `INV-AC-2251 the tenant admin cannot approve by default, the officer can, time-boxed and audited`() =
        run("SAAS") { env ->
            // VP-07 is planned in the pinned catalogue, so the tenant.admin option is not in force (INV-REQ-1268)
            val vp07 = GstVariationPointCatalogue("access-svc").find(VariationPointKey("VP-07"))
            assertThat(vp07!!.status).isEqualTo(VpStatus.PLANNED)
            val ref = env.request().requestRef
            val before = env.rows()
            assertThat(refusal { env.decide(ref, actor = env.admin) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.rows()).isEqualTo(before)
            assertThat(env.decision(env.support, content)).isEqualTo(deny)
            val decided = env.decide(ref).body as ElevationResponse
            assertThat(decided.status).isEqualTo(ElevationResponse.Status.GRANTED)
            assertThat(env.decision(env.support, content)).isEqualTo(allow)
            env.clock.advance(Duration.ofMinutes(31))
            assertThat(env.decision(env.support, content)).isEqualTo(deny)
            assertThat(env.auditActions()).contains("approveElevation")
        }

    @Test
    fun `INV-AC-1913 an operator of a private installation has no access without an approved invitation`() =
        run("PRIVATE") { env ->
            val key = "support-bundle.export"
            assertThat(env.decision(env.operator, key)).describedAs("no invitation").isEqualTo(deny)
            assertThat(env.ask(env.operator, key).reasonCode).isEqualTo(OperatorAccess.NO_INVITATION)
            val invitation = env.invite()
            assertThat(invitation.invitationRef).startsWith("DIAG-")
            assertThat(env.decision(env.operator, key)).describedAs("pending").isEqualTo(deny)
            env.decide(invitation.invitationRef)
            assertThat(env.decision(env.operator, key)).describedAs("approved").isEqualTo(allow)
            assertThat(env.auditActions()).contains("inviteDiagnostics", "approveElevation", "operatorDiagnosticAccess")
        }

    @Test
    fun `INV-AC-7950 DENY with no, a pending or a withdrawn invitation and ALLOW only inside the approved time box`() =
        run("PRIVATE") { env ->
            val key = "support-bundle.export"
            assertThat(env.decision(env.operator, key)).describedAs("none").isEqualTo(deny)
            val pending = env.invite(case = "CASE-P")
            assertThat(env.decision(env.operator, key)).describedAs("pending").isEqualTo(deny)
            assertThat(env.withdrawInvitation(pending.invitationRef).statusCode.value()).isEqualTo(204)
            assertThat(env.decision(env.operator, key)).describedAs("withdrawn").isEqualTo(deny)
            val second = env.invite(case = "CASE-2")
            env.decide(second.invitationRef)
            assertThat(env.decision(env.operator, key)).describedAs("inside the box").isEqualTo(allow)
            env.clock.advance(Duration.ofMinutes(31))
            assertThat(env.decision(env.operator, key)).describedAs("after the box").isEqualTo(deny)
            assertThat(env.auditActions()).contains("withdrawDiagnosticInvitation", "operatorDiagnosticAccess")
        }

    @Test
    fun `INV-AC-1930 a refused and a withdrawn elevation open nothing and cannot be decided again`() =
        run("SAAS") { env ->
            val refused = env.request(case = "CASE-R").requestRef
            env.decide(refused, grant = false)
            assertThat(env.decision(env.support, content)).describedAs("after refusal").isEqualTo(deny)
            val withdrawn = env.request(case = "CASE-W").requestRef
            assertThat(env.withdrawElevation(withdrawn).statusCode.value()).isEqualTo(204)
            assertThat(env.decision(env.support, content)).describedAs("after withdrawal").isEqualTo(deny)
            assertThat(catchThrowable { env.decide(withdrawn) }).isInstanceOf(ApiConflict::class.java)
        }

    @Test
    fun `INV-AC-7953 each operation asks for its own permission key and no role name decides`() =
        run("SAAS") { env ->
            // elevation.decide only (the officer) asking for elevation.request; elevation.request only asking to decide
            assertThat(refusal { env.request(actor = env.officer) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(refusal { env.request(actor = env.admin) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            val ref = env.request().requestRef
            assertThat(refusal { env.decide(ref, actor = env.support) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(refusal { env.withdrawElevation(ref, actor = env.officer) })
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            // elevation.request only (support) asking to invite; the SAAS form has no diagnostic.invite cell
            assertThat(refusal { env.invite(actor = env.support) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(refusal { env.invite(actor = env.operator) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        }

    @Test
    fun `INV-AC-7950 in the private form the operator asks for diagnostic invite and not for elevation request`() =
        run("PRIVATE") { env ->
            assertThat(refusal { env.request(actor = env.operator) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            val invitation = env.invite()
            assertThat(refusal { env.withdrawInvitation(invitation.invitationRef, actor = env.officer) })
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(refusal { env.invite(actor = env.officer) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        }

    @Test
    fun `INV-AC-2251 the officer of another tenant cannot decide a DIAG reference of the first tenant`() =
        run("PRIVATE") { env ->
            val invitation = env.invite()
            val before = env.rows()
            val code = refusal { env.decide(invitation.invitationRef, actor = env.officerB, tenant = env.b) }
            assertThat(code).isEqualTo(ClosedErrorCode.NOT_FOUND)
            assertThat(env.rows()).isEqualTo(before)
            // the invitation row carries the tenant bound for the request and the installation as its subject
            val row =
                BoundaryFixture.asSuperuser { c ->
                    c.createStatement().use { s ->
                        s
                            .executeQuery(
                                "select tenant_id::text, subject_ref from access.elevation " +
                                    "where elevation_ref = '${invitation.invitationRef}'",
                            ).use { r ->
                                r.next()
                                r.getString(1) to r.getString(2)
                            }
                    }
                }
            assertThat(row).isEqualTo(env.a.toString() to "INST-1")
        }

    @Test
    fun `INV-AC-7950 an approved invitation of tenant A opens nothing for a scope of tenant B`() =
        run("PRIVATE") { env ->
            val key = "support-bundle.export"
            env.decide(env.invite().invitationRef)
            assertThat(env.decision(env.operator, key, "ENT-A", env.a)).isEqualTo(allow)
            assertThat(env.decision(env.operator, key, "ENT-B", env.b)).isEqualTo(deny)
            assertThat(env.decision(env.operator, key, "ENT-B", env.a)).isEqualTo(deny)
        }

    @Test
    fun `INV-AC-1930 an approved elevation opens exactly doc view content in its tenant`() =
        run("SAAS") { env ->
            env.decide(env.request().requestRef)
            assertThat(OperatorAccess.OPENED_BY_ELEVATION).containsExactly(Permission(content))
            assertThat(env.decision(env.support, content)).isEqualTo(allow)
            val others =
                listOf("audit-pack.export", "doc.view.contents", "Doc.View.Content", "$content ", "audit-log.view")
            others.forEach {
                assertThat(env.decision(env.support, it)).describedAs(it).isEqualTo(deny)
            }
            // another tenant's content, asked in the bound tenant of A and of B
            assertThat(env.decision(env.support, content, "ENT-B", env.b)).isEqualTo(deny)
            assertThat(env.decision(env.support, content, "ENT-B", env.a)).isEqualTo(deny)
            // another identity gets nothing from the elevation
            assertThat(env.decision(user("other-support"), content)).isEqualTo(deny)
        }

    @Test
    fun `INV-AC-922 a repeat request returns the pending one and a bad case is a contract violation`() =
        run("SAAS") { env ->
            val first = env.request()
            assertThat(env.request().requestRef).isEqualTo(first.requestRef)
            // the controller's validation (the contract's caseId pattern) refuses these before the use case runs ...
            listOf("", "  ", "A:B", "x".repeat(65), "bad\u0007").forEach {
                assertThat(catchThrowable { env.request(case = it) }.javaClass.simpleName)
                    .describedAs(it)
                    .isEqualTo("ConstraintViolationException")
            }
            // ... and the use case holds the same rule for a caller that skips it
            val open = env.context.getBean(OpenElevationUseCase::class.java)
            listOf("", "  ", "A:B", "x".repeat(65), "bad\u0007", "\u00A0\u3000").forEach {
                val ask = ElevationAsk(ElevationKind.ELEVATION, env.a.toString(), IdentityRef(env.support), it)
                val outcome = env.into(env.a, env.support) { open.handle(IdentityRef(env.support), ask, null) }
                assertThat(outcome).describedAs(it).isEqualTo(ElevationOutcome.BadCase)
            }
            // a request is for the bound tenant only
            val other =
                refusal {
                    env.into(env.a, env.support) {
                        env.api.requestElevation(UUID.randomUUID(), ElevationRequest("CASE-9", env.b.toString(), "x"))
                    }
                }
            assertThat(other).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        }

    @Test
    fun `INV-AC-2251 an elevation closes at once when the operator's platform grant is revoked after approval`() =
        run("SAAS") { env ->
            env.decide(env.request().requestRef)
            assertThat(env.decision(env.support, content)).isEqualTo(allow)
            env.revokePlatformGrant(env.support)
            assertThat(env.decision(env.support, content)).isEqualTo(deny)
        }

    @Test
    fun `INV-AC-2251 the time box is at most 1440 minutes, one more is refused`() =
        run("SAAS") { env ->
            val max = ElevationKeys.MAX_VALID_MINUTES
            assertThat(max).isEqualTo(1440)
            env.decide(env.request().requestRef, minutes = max)
            assertThat(env.decision(env.support, content)).isEqualTo(allow)
            // the contract's maximum refuses the controller call before the use case runs ...
            val second = env.request(case = "CASE-2")
            assertThat(catchThrowable { env.decide(second.requestRef, minutes = max + 1) }.javaClass.simpleName)
                .isEqualTo("ConstraintViolationException")
            // ... and the use case holds the same bound for a caller that skips it
            val decide = env.context.getBean(DecideElevationUseCase::class.java)
            listOf(max + 1, 0, -1, Int.MAX_VALUE).forEach {
                val outcome =
                    env.into(
                        env.a,
                        env.officer,
                    ) { decide.handle(IdentityRef(env.officer), second.requestRef, true, it) }
                assertThat(outcome).describedAs("$it").isEqualTo(ElevationOutcome.BadDecision)
            }
        }

    @Test
    fun `INV-AC-7953 a replay of an Idempotency-Key returns the stored answer, another body under it is refused`() =
        run("SAAS") { env ->
            val key = UUID.randomUUID()
            val ref = env.request().requestRef
            val first = env.decide(ref, key = key)
            val replay = env.decide(ref, key = key)
            assertThat(replay.statusCode).isEqualTo(first.statusCode)
            assertThat(replay.body).isEqualTo(first.body)
            // without the key a second decision of the settled record is a conflict
            assertThat(catchThrowable { env.decide(ref) }).isInstanceOf(ApiConflict::class.java)
            // the same key with another body
            assertThat(
                refusal { env.decide(ref, grant = false, key = key) },
            ).isEqualTo(ClosedErrorCode.DIFFERENT_REQUEST)
            // a withdraw replay answers the same 204 after the record was withdrawn once
            val other = env.request(case = "CASE-3").requestRef
            val withdrawKey = UUID.randomUUID()
            val one = env.into(env.a, env.support) { env.api.withdrawElevationRequest(other, withdrawKey) }
            val two = env.into(env.a, env.support) { env.api.withdrawElevationRequest(other, withdrawKey) }
            assertThat(one.statusCode).isEqualTo(two.statusCode)
            // a replayed request returns the stored 201 and body, and writes no second row
            val askKey = UUID.randomUUID()
            val ask = ElevationRequest("CASE-4", env.a.toString(), "investigate")
            val r1 = env.into(env.a, env.support) { env.api.requestElevation(askKey, ask) }
            val r2 = env.into(env.a, env.support) { env.api.requestElevation(askKey, ask) }
            assertThat(r2.statusCode).isEqualTo(r1.statusCode)
            assertThat(r2.body).isEqualTo(r1.body)
        }
}
