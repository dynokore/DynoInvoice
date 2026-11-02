package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.SealFixture.accountRows
import com.dynokore.gst.access.bootstrap.SealFixture.activated
import com.dynokore.gst.access.bootstrap.SealFixture.pair
import com.dynokore.gst.access.bootstrap.SealFixture.passExpiry
import com.dynokore.gst.access.bootstrap.SealFixture.reseal
import com.dynokore.gst.access.bootstrap.SealFixture.signIn
import com.dynokore.gst.access.bootstrap.SealFixture.unlocked
import com.dynokore.gst.access.contract.model.BreakGlassSignInRequest
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/**
 * `signInBreakGlass` (INV-REQ-896, INV-REQ-1262, INV-Q-8661 A; INV-AC-8557 and the sign-in half of INV-AC-2244): the
 * account signs in while the unlock is open; a wrong credential or no account row is 403; no open unlock, an expired
 * session or a resealed one is 409 with no catalogue code (INV-Q-8845 A).
 */
class BreakGlassSignInTest {
    /** 409 with a problem body that names no catalogue code (never SRT-008, "Stale aggregate version"). */
    private fun assertConflictWithoutCode(response: MockHttpServletResponse) {
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(409)
        val body = json(response)
        assertThat(body.has("code")).describedAs("the 409 body carries no code").isFalse()
        assertThat(body["detail"].asString()).isNotBlank()
        assertThat(response.contentAsString).doesNotContain("SRT-008")
    }

    private fun insertSession(
        tenant: TenantId,
        unlockRef: String,
    ) = BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "insert into access.break_glass_session (id, tenant_id, unlock_ref, session_hours, unlocked_at, " +
                    "expires_at, approved_by, status, created_at, created_by, updated_at, updated_by, version) " +
                    "values (?::uuid, ?::uuid, ?, 4, now(), now() + interval '4 hours', " +
                    "array['IDN-ADM','IDN-APP'], 'UNLOCKED', now(), 'seed', now(), 'seed', 0)",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, tenant.toString())
                it.setString(3, unlockRef)
                it.executeUpdate()
            }
    }

    @Test
    fun `INV-AC-8557 the account signs in and a wrong, another tenant's or no credential is 403`() =
        withService { served ->
            val a = served.activated()
            val b = served.activated()
            served.pair(a.tenant)
            served.pair(b.tenant)
            val credentialA = checkNotNull(a.sealing)
            val credentialB = checkNotNull(b.sealing)
            val (refA, _) = served.unlocked(a.tenant)
            val (refB, _) = served.unlocked(b.tenant)
            val ok = served.signIn(a.tenant, refA, credentialA)
            assertThat(ok.status).describedAs(ok.contentAsString).isEqualTo(200)
            assertThat(json(ok)["unlockRef"].asString()).isEqualTo(refA)
            assertThat(json(ok)["tenantRef"].asString()).isEqualTo(a.tenant.toString())
            val permitted = json(ok)["permittedActions"]
            assertThat(permitted.size()).isEqualTo(4)
            assertThat(permitted.toString()).contains("\"VIEW\"", "\"READINESS\"", "\"DRAIN\"", "\"REPLAY\"")
            // the credential of another tenant's account on this tenant's unlock
            assertThat(served.signIn(a.tenant, refA, credentialB).status).isEqualTo(403)
            assertThat(served.signIn(b.tenant, refB, credentialA).status).isEqualTo(403)
            assertThat(served.signIn(b.tenant, refB, credentialB).status).isEqualTo(200)
            // a wrong credential, one character off
            val lastChar = if (credentialA.last() == 'A') "B" else "A"
            assertThat(served.signIn(a.tenant, refA, credentialA.dropLast(1) + lastChar).status).isEqualTo(403)
            // a tenant with no account row: fail closed, the same 403
            val bare = newTenant()
            served.pair(bare)
            val (refBare, _) = served.unlocked(bare)
            assertThat(accountRows(bare)).isEmpty()
            assertThat(served.signIn(bare, refBare, credentialA).status).isEqualTo(403)
            // an unknown unlock
            assertThat(served.signIn(a.tenant, "BG-NONE", credentialA).status).isEqualTo(404)
        }

    @Test
    fun `INV-AC-8557 the credential of the look-alike unlock BG-0003 fails against BG-0030`() =
        withService { served ->
            val a = served.activated()
            val b = served.activated()
            insertSession(a.tenant, "BG-0003")
            insertSession(b.tenant, "BG-0030")
            val credentialA = checkNotNull(a.sealing)
            assertThat(served.signIn(a.tenant, "BG-0003", credentialA).status).isEqualTo(200)
            assertThat(served.signIn(b.tenant, "BG-0030", credentialA).status).isEqualTo(403)
            // the unlock of the look-alike reference does not exist for this tenant at all
            assertThat(served.signIn(a.tenant, "BG-0030", credentialA).status).isEqualTo(404)
            assertThat(served.signIn(b.tenant, "BG-0030", checkNotNull(b.sealing)).status).isEqualTo(200)
        }

    @Test
    fun `INV-AC-8557 one second after expiresAt and after a reseal the sign-in is 409 with no code`() =
        withService { served ->
            val a = served.activated()
            served.pair(a.tenant)
            val credential = checkNotNull(a.sealing)
            val (resealed, _) = served.unlocked(a.tenant)
            val (expiring, expiresAt) = served.unlocked(a.tenant)
            assertThat(served.signIn(a.tenant, expiring, credential).status).isEqualTo(200)
            val rotated = json(served.reseal(a.tenant, resealed))["sealingCredential"].asString()
            // the resealed unlock: the old and the new credential are both refused with 409
            assertConflictWithoutCode(served.signIn(a.tenant, resealed, credential))
            assertConflictWithoutCode(served.signIn(a.tenant, resealed, rotated))
            // at expiresAt the session is already closed; one second after it for good
            served.passExpiry(expiresAt)
            assertConflictWithoutCode(served.signIn(a.tenant, expiring, rotated))
        }

    @Test
    fun `INV-AC-8557 INV-AC-2244 the account is present before and after the switch to the customer directory`() {
        val tenant = newTenant()
        val (unlockRef, credential, before) = openedSession(tenant)
        IdentityProviderSimulator().start().use { customer ->
            customer.addAudience("access-svc")
            val member = customer.userToken("tenant-admin-1")
            BoundaryFixture
                .runner()
                .withPropertyValues("access.identity-provider.issuer-uri=${customer.issuer}")
                .run { context ->
                    context.startupFailure?.let { throw it }
                    // users sign in through the customer directory
                    val resolver = context.getBean(EndUserPrincipalResolver::class.java)
                    assertThat(resolver.resolve("Bearer $member")).isNotNull()
                    // the sealed account of the tenant is present after the switch, as it was before
                    val after = accountRows(tenant).size
                    assertThat(before).isEqualTo(1)
                    assertThat(after).isEqualTo(before)
                    val signIn = {
                        directSignIn(
                            context.getBean(BreakGlassController::class.java),
                            context.getBean(TenantContext::class.java),
                            tenant,
                            unlockRef,
                            credential,
                        )
                    }
                    assertThat(signIn().statusCode.value()).isEqualTo(200)
                    // probe: a switch that removes the sealed account makes the presence comparison fail
                    BoundaryFixture.asSuperuser { c ->
                        c.prepareStatement("delete from access.break_glass_account where tenant_id = ?::uuid").use {
                            it.setString(1, tenant.toString())
                            it.executeUpdate()
                        }
                    }
                    assertThat(accountRows(tenant).size).isNotEqualTo(before)
                    assertThat(runCatching { signIn() }.exceptionOrNull()).isInstanceOf(ApiRefusal::class.java)
                }
        }
    }

    private fun directSignIn(
        api: BreakGlassController,
        tenants: TenantContext,
        tenant: TenantId,
        unlockRef: String,
        credential: String,
    ) = tenants.bind(tenant, SealFixture.ADMIN) {
        api.signInBreakGlass(unlockRef, UUID.randomUUID(), BreakGlassSignInRequest(credential))
    }

    /** Accepts, unlocks and signs in once for [tenant]; returns the unlock, the credential and the row count. */
    private fun openedSession(tenant: TenantId): Triple<String, String, Int> {
        var opened: Triple<String, String, Int>? = null
        withService { served ->
            val credential = checkNotNull(served.activated(tenant).sealing)
            served.pair(tenant)
            val unlockRef = served.unlocked(tenant).first
            assertThat(served.signIn(tenant, unlockRef, credential).status).isEqualTo(200)
            opened = Triple(unlockRef, credential, accountRows(tenant).size)
        }
        return checkNotNull(opened)
    }
}
