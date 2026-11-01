package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.accept
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.movableClock
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.provisioned
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.token
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CONTROL_PLANE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.LOOK_ALIKE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.OTHER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.adminGrants
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.first
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.operation
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.seedAdminRow
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.snapshot
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.served.OPERATOR_TOKEN
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.bootstrap.served.USER_A_TOKEN
import com.dynokore.gst.access.bootstrap.served.workloadToken
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

private const val NEW_EXPIRY = "2031-02-03T04:05:06Z"
private const val ACTION = "reissueFirstAdministratorInvitation"

/**
 * INV-AC-8281 (INV-T-8282; INV-Q-8232 A): `reissueFirstAdministratorInvitation` through the real context over a real
 * PostgreSQL with forced row-level security, the generated caller check and the request filters in front of the
 * controller. The clock is the service clock port ([MovableClock]).
 */
class ReissueFirstAdministratorInvitationTest {
    private fun ServedContext.reissue(
        tenant: TenantId,
        caller: String? = CONTROL_PLANE,
        auth: String? = OPERATOR_TOKEN,
        expires: String = NEW_EXPIRY,
        key: UUID = UUID.randomUUID(),
    ): MockHttpServletResponse {
        val headers = mutableMapOf<String, String>()
        (application.getBean(ServiceCaller::class.java) as? MovableServiceCaller)?.value = caller
        caller?.let { headers["X-Workload-Token"] = workloadToken(it) }
        auth?.let { headers["Authorization"] = it }
        val op = operation(ACTION)
        return post(
            op,
            headers,
            """{"invitationExpiresAt":"$expires"}""",
            key,
            op.path.replace("{tenantRef}", "$tenant"),
        )
    }

    /** A provisioned invitation whose stored expiry is [secondsAhead] after the frozen clock. */
    private fun ServedContext.provisionedExpiring(
        secondsAhead: Long,
        tenant: TenantId = newTenant(),
    ): Pair<Invitation, Instant> {
        val expiry = movableClock().freeze().plusSeconds(secondsAhead)
        return provisioned(tenant, expires = expiry.toString()) to expiry
    }

    private fun ServedContext.expired(tenant: TenantId = newTenant()): Invitation {
        val (invitation, expiry) = provisionedExpiring(10, tenant)
        movableClock().advance(Duration.ofSeconds(11))
        assertThat(movableClock().instant()).isAfter(expiry)
        return invitation
    }

    private fun invitationRows(tenant: TenantId) = rows("access.first_administrator_invitation", tenant).map(::json)

    private fun auditEntries(tenant: TenantId): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select data::text from access.outbox where tenant_id = ?::uuid " +
                        "and type like '%AuditEntryRecorded%' and data::text like '%$ACTION%'",
                ).use {
                    it.setString(1, tenant.toString())
                    it.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    private fun ServedContext.refusedWithNothingStored(
        tenant: TenantId,
        status: Int,
        call: () -> MockHttpServletResponse = { reissue(tenant) },
    ) {
        val before = snapshot(tenant)
        val events = stored()
        val audit = auditEntries(tenant)
        assertThat(call().status).isEqualTo(status)
        assertThat(snapshot(tenant)).isEqualTo(before)
        assertThat(stored()).isEqualTo(events)
        assertThat(auditEntries(tenant)).isEqualTo(audit)
    }

    private fun grantStatus(tenant: TenantId): String = json(adminGrants(tenant).single())["status"].asString()

    // ---- the reissue ----

    @Test
    fun `INV-AC-8281 an expired pending first administrator invitation is reissued on the same grant`() =
        withService { served ->
            val tenant = newTenant()
            val old = served.expired(tenant)
            val before = snapshot(tenant)
            val codes = FirstAdministratorFixture.codes(served)
            val oldDigest = codes.digestOf(old.code)
            val response = served.reissue(tenant)
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            val body = json(response)
            assertThat(body["grantRef"].asString()).isEqualTo(old.grantRef)
            assertThat(body["status"].asString()).isEqualTo("PENDING")
            val newRef = body["invitationRef"].asString()
            val newCode = body["invitationCode"].asString()
            assertThat(newRef).isNotEqualTo(old.ref)
            assertThat(newCode).isNotEqualTo(old.code).isEqualTo(FirstAdministratorFixture.expectedCode(tenant, newRef))
            // the stored invitation holds the new reference, digest and expiry; no grant or identity row changed
            val row = invitationRows(tenant).single()
            assertThat(row["invitation_ref"].asString()).isEqualTo(newRef)
            assertThat(row["code_digest"].asString()).isEqualTo(codes.digestOf(newCode))
            assertThat(Instant.parse(row["expires_at"].asString().replace(" ", "T").replace("+00:00", "Z")))
                .isEqualTo(Instant.parse(NEW_EXPIRY))
            assertThat(row["grant_ref"].asString()).isEqualTo(old.grantRef)
            val after = snapshot(tenant)
            assertThat(after["access.role_grant"]).isEqualTo(before["access.role_grant"])
            assertThat(after["access.identity"]).isEqualTo(before["access.identity"])
            assertThat(adminGrants(tenant)).hasSize(1)
            // no column of any stored row holds the new code in plaintext or the digest of the old one
            val everything = after.values.flatten().joinToString("\n")
            assertThat(everything).doesNotContain(newCode).doesNotContain(oldDigest)
        }

    @Test
    fun `INV-AC-8281 the old reference and the old code are refused, the new code accepts before the new expiry`() =
        withService { served ->
            val tenant = newTenant()
            val old = served.expired(tenant)
            val newExpiry =
                served
                    .movableClock()
                    .instant()
                    .plusSeconds(600)
                    .toString()
            val body = json(served.reissue(tenant, expires = newExpiry))
            val newRef = body["invitationRef"].asString()
            val newCode = body["invitationCode"].asString()
            // the refusal this service gives for a wrong code
            val wrong = served.accept(newRef, "not-the-code", token("probe-0"))
            assertThat(wrong.status).isEqualTo(404)
            val oldRefOldCode = served.accept(old.ref, old.code, token("invitee-1"))
            assertThat(oldRefOldCode.status).isEqualTo(wrong.status)
            assertThat(json(oldRefOldCode)["code"]).isEqualTo(json(wrong)["code"])
            val newRefOldCode = served.accept(newRef, old.code, token("invitee-1"))
            assertThat(newRefOldCode.status).isEqualTo(wrong.status)
            assertThat(json(newRefOldCode)["code"]).isEqualTo(json(wrong)["code"])
            assertThat(grantStatus(tenant)).isEqualTo("PENDING")
            val accepted = served.accept(newRef, newCode, token("invitee-1"))
            assertThat(accepted.status).describedAs(accepted.contentAsString).isEqualTo(200)
            assertThat(grantStatus(tenant)).isEqualTo("ACTIVE")
            assertThat(json(adminGrants(tenant).single())["identity_ref"].asString()).isEqualTo("invitee-1")
        }

    // ---- the 409 rule ----

    @Test
    fun `INV-AC-8281 a pending invitation that has not expired is refused with 409`() =
        withService { served ->
            val tenant = newTenant()
            served.provisionedExpiring(3600, tenant)
            served.refusedWithNothingStored(tenant, 409)
        }

    @Test
    fun `INV-AC-8281 at the exact stored expiry instant the reissue is 409 and the acceptance succeeds`() =
        withService { served ->
            val tenant = newTenant()
            val (invitation, expiry) = served.provisionedExpiring(30, tenant)
            served.movableClock().advance(Duration.ofSeconds(30))
            assertThat(served.movableClock().instant()).isEqualTo(expiry)
            served.refusedWithNothingStored(tenant, 409)
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-1")).status).isEqualTo(200)
        }

    @Test
    fun `INV-AC-8281 one tick after the stored expiry the reissue is 200`() =
        withService { served ->
            val tenant = newTenant()
            val (_, expiry) = served.provisionedExpiring(30, tenant)
            served.movableClock().advance(Duration.ofSeconds(30).plusNanos(1_000))
            assertThat(served.movableClock().instant()).isEqualTo(expiry.plusNanos(1_000))
            assertThat(served.reissue(tenant).status).isEqualTo(200)
        }

    @Test
    fun `INV-AC-8281 an ACTIVE tenant admin row is refused with 409`() =
        withService { served ->
            val tenant = newTenant()
            seedAdminRow(tenant, "ACTIVE")
            served.refusedWithNothingStored(tenant, 409)
        }

    @Test
    fun `INV-AC-8281 a tenant with no tenant admin row is refused with 409`() =
        withService { served -> served.refusedWithNothingStored(newTenant(), 409) }

    @Test
    fun `INV-AC-8281 a REVOKED only tenant is refused with 409`() =
        withService { served ->
            val tenant = newTenant()
            seedAdminRow(tenant, "REVOKED")
            served.refusedWithNothingStored(tenant, 409)
        }

    @Test
    fun `INV-AC-8281 a pending row that has no invitation is refused with 409`() =
        withService { served ->
            val tenant = newTenant()
            seedAdminRow(tenant, "PENDING")
            served.refusedWithNothingStored(tenant, 409)
        }

    @Test
    fun `INV-AC-8281 an expired pending row together with another active admin made through grantRole is 409`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val context = served.application
            context.getBean(TenantContext::class.java).bind(tenant, "IDN-GRANTER") {
                context.getBean(GrantRoleUseCase::class.java).handle(
                    GrantRoleCommand(
                        tenant = TenantRef(tenant.toString()),
                        actor = "IDN-GRANTER",
                        identity = IdentityRef("IDN-VIA-GRANT-ROLE"),
                        granteeKind = GranteeKind.STAFF,
                        roleWire = Role.TENANT_ADMIN.wire,
                        scope = Scope.Tenant(TenantRef(tenant.toString())),
                        window = ValidityWindow(context.getBean(Clock::class.java).instant(), null),
                    ),
                )
            }
            assertThat(adminGrants(tenant)).hasSize(2)
            served.refusedWithNothingStored(tenant, 409)
        }

    @Test
    fun `INV-AC-8281 a row of another tenant neither makes the call succeed nor fail`() =
        withService { served ->
            val other = newTenant()
            seedAdminRow(other, "ACTIVE")
            val mine = served.expired()
            assertThat(served.reissue(mine.tenant).status).isEqualTo(200)
            served.refusedWithNothingStored(newTenant(), 409)
            assertThat(adminGrants(other)).hasSize(1)
            assertThat(json(adminGrants(other).single())["status"].asString()).isEqualTo("ACTIVE")
        }

    @Test
    fun `INV-AC-8281 two concurrent calls with two keys for one tenant give one 200 and one 409`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val start = CountDownLatch(1)
                val calls =
                    List(2) {
                        pool.submit(
                            Callable {
                                start.await()
                                served.reissue(tenant, key = UUID.randomUUID()).status
                            },
                        )
                    }
                start.countDown()
                assertThat(calls.map { it.get() }).containsExactlyInAnyOrder(200, 409)
            } finally {
                pool.shutdownNow()
            }
            assertThat(adminGrants(tenant)).hasSize(1)
            assertThat(invitationRows(tenant)).hasSize(1)
        }

    @Test
    fun `INV-AC-8281 provisionFirstAdministrator with a new key is still 409 after a reissue`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            assertThat(served.reissue(tenant).status).isEqualTo(200)
            val before = snapshot(tenant)
            assertThat(served.first(tenant).status).isEqualTo(409)
            assertThat(snapshot(tenant)).isEqualTo(before)
        }

    // ---- the caller ----

    private fun refusedCaller(
        caller: String,
        auth: String?,
    ) = withService { served ->
        val tenant = newTenant()
        served.expired(tenant)
        served.refusedWithNothingStored(tenant, 403) { served.reissue(tenant, caller, auth) }
    }

    @Test
    fun `INV-AC-8281 a person holding commercial admin, through console-bff, is refused with 403`() =
        refusedCaller(FirstAdministratorFixture.CONSOLE_BFF, OPERATOR_TOKEN)

    @Test
    fun `INV-AC-8281 a person holding tenant admin, through console-bff, is refused with 403`() =
        refusedCaller(FirstAdministratorFixture.CONSOLE_BFF, USER_A_TOKEN)

    @Test
    fun `INV-AC-8281 the look-alike caller is refused with 403`() = refusedCaller(LOOK_ALIKE, OPERATOR_TOKEN)

    @Test
    fun `INV-AC-8281 the installer workload is refused with 403`() = refusedCaller(INSTALLER, null)

    @Test
    fun `INV-AC-8281 another service is refused with 403`() = refusedCaller(OTHER, OPERATOR_TOKEN)

    // ---- the audit ----

    @Test
    fun `INV-AC-8281 one audit entry per reissue carries the tenant, grant, both references and the caller`() =
        withService { served ->
            val tenant = newTenant()
            val old = served.expired(tenant)
            val response = json(served.reissue(tenant))
            val entries = auditEntries(tenant)
            assertThat(entries).hasSize(1)
            val entry = json(entries.single())
            assertThat(entry["action"].asString()).isEqualTo(ACTION)
            assertThat(entry["actor"].asString()).isEqualTo("operator-1")
            assertThat(entry["scope"].asString()).isEqualTo("TENANT:$tenant")
            val state = entry["priorState"]
            assertThat(state["tenantRef"].asString()).isEqualTo(tenant.toString())
            assertThat(state["grantRef"].asString()).isEqualTo(old.grantRef)
            assertThat(state["oldInvitationRef"].asString()).isEqualTo(old.ref)
            assertThat(state["newInvitationRef"].asString()).isEqualTo(response["invitationRef"].asString())
            val codes = FirstAdministratorFixture.codes(served)
            assertThat(entries.single())
                .doesNotContain(response["invitationCode"].asString())
                .doesNotContain(old.code)
                .doesNotContain(codes.digestOf(old.code))
                .doesNotContain(codes.digestOf(response["invitationCode"].asString()))
        }
}
