package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.SealFixture.activated
import com.dynokore.gst.access.bootstrap.SealFixture.capturedEvents
import com.dynokore.gst.access.bootstrap.SealFixture.digestOf
import com.dynokore.gst.access.bootstrap.SealFixture.pair
import com.dynokore.gst.access.bootstrap.SealFixture.passExpiry
import com.dynokore.gst.access.bootstrap.SealFixture.reseal
import com.dynokore.gst.access.bootstrap.SealFixture.sessionStatus
import com.dynokore.gst.access.bootstrap.SealFixture.signIn
import com.dynokore.gst.access.bootstrap.SealFixture.unlocked
import com.dynokore.gst.access.bootstrap.SealFixture.verifier
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.contract.model.BreakGlassActionRequest
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/**
 * `resealBreakGlass` (INV-REQ-897 behaviour 4; INV-AC-8852 and the rotate part of INV-AC-1934): closes the unlock,
 * replaces the sealing credential and returns the new one once; only a holder of the permission `break-glass.reseal`
 * may call it (INV-Q-16100 A; nothing else is checked: INV-Q-8895 is open); a reseal after the expiry is 409 with no
 * catalogue code.
 */
class BreakGlassResealTest {
    private fun ServedContext.record(
        tenant: TenantId,
        unlockRef: String,
    ): ResponseEntity<*> {
        (application.getBean(ServiceCaller::class.java) as MovableServiceCaller).value = "ingestion-svc"
        return tenants.bind(tenant, SealFixture.ADMIN) {
            application
                .getBean(BreakGlassController::class.java)
                .recordBreakGlassAction(
                    unlockRef,
                    UUID.randomUUID(),
                    BreakGlassActionRequest(BreakGlassActionRequest.Action.VIEW),
                )
        }
    }

    private fun assertConflictWithoutCode(response: MockHttpServletResponse) {
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(409)
        assertThat(json(response).has("code")).isFalse()
        assertThat(json(response)["detail"].asString()).isNotBlank()
    }

    @Test
    fun `INV-AC-8852 INV-AC-1934 the reseal replaces the verifier and closes the unlock for sign-in and for actions`() =
        withService { served ->
            val account = served.activated()
            val tenant = account.tenant
            served.pair(tenant)
            val original = checkNotNull(account.sealing)
            val (ref, _) = served.unlocked(tenant)
            val before = verifier(tenant)
            assertThat(before).isEqualTo(digestOf(original))
            // the session is used: a sign-in and a recorded action
            assertThat(served.signIn(tenant, ref, original).status).isEqualTo(200)
            assertThat(served.record(tenant, ref).statusCode.value()).isEqualTo(201)
            // the call of a deployment.operator
            val response = served.reseal(tenant, ref)
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            assertThat(json(response)["status"].asString()).isEqualTo("SEALED")
            assertThat(json(response)["unlockRef"].asString()).isEqualTo(ref)
            val rotated = json(response)["sealingCredential"].asString()
            assertThat(verifier(tenant)).isNotEqualTo(before).isEqualTo(digestOf(rotated))
            assertThat(sessionStatus(tenant, ref)).isEqualTo("SEALED")
            // sign-in and recording on the resealed unlock: 409 and no catalogue code
            assertConflictWithoutCode(served.signIn(tenant, ref, original))
            assertConflictWithoutCode(served.signIn(tenant, ref, rotated))
            val refusal = catchThrowable { served.record(tenant, ref) }
            assertThat(refusal).isInstanceOf(ApiConflict::class.java)
            val problem = ApiRefusalAdvice().conflict(refusal as ApiConflict)
            assertThat(problem.statusCode.value()).isEqualTo(409)
            assertThat(problem.body).doesNotContain("\"code\"")
            // the reseal is audit-logged once, under the acting user
            val audits = capturedEvents(served).filter { it.contains("resealBreakGlass") }
            assertThat(audits).hasSize(1)
            assertThat(audits.single()).contains(SealFixture.OPERATOR)
        }

    @Test
    fun `INV-AC-8852 a caller without the permission break-glass-reseal is 403 and nothing changes`() =
        withService { served ->
            val account = served.activated()
            val tenant = account.tenant
            served.pair(tenant)
            val (ref, _) = served.unlocked(tenant)
            val verifierBefore = verifier(tenant)
            val statusBefore = sessionStatus(tenant, ref)
            val auditBefore = capturedEvents(served).filter { it.contains("resealBreakGlass") }
            val everything = served.stored()
            // the tenant admin, an installation role that is not deployment.operator, and an unknown user
            listOf(SealFixture.ADMIN, "user:eng-1", "user:nobody").forEach { caller ->
                val refused = served.reseal(tenant, ref, caller)
                assertThat(refused.status).describedAs("$caller: ${refused.contentAsString}").isEqualTo(403)
                assertThat(json(refused).has("sealingCredential")).isFalse()
            }
            assertThat(verifier(tenant)).isEqualTo(verifierBefore)
            assertThat(sessionStatus(tenant, ref)).isEqualTo(statusBefore)
            assertThat(capturedEvents(served).filter { it.contains("resealBreakGlass") }).isEqualTo(auditBefore)
            assertThat(served.stored()).isEqualTo(everything)
            // the session is still open
            assertThat(served.signIn(tenant, ref, checkNotNull(account.sealing)).status).isEqualTo(200)
        }

    @Test
    fun `INV-AC-8852 a reseal one second after expiresAt is 409 with no code and nothing changes`() =
        withService { served ->
            val account = served.activated()
            val tenant = account.tenant
            served.pair(tenant)
            val (ref, expiresAt) = served.unlocked(tenant)
            val verifierBefore = verifier(tenant)
            served.passExpiry(expiresAt)
            val response = served.reseal(tenant, ref)
            assertConflictWithoutCode(response)
            assertThat(json(response).has("sealingCredential")).isFalse()
            assertThat(verifier(tenant)).isEqualTo(verifierBefore)
            assertThat(sessionStatus(tenant, ref)).isEqualTo("UNLOCKED")
            // an unknown unlock
            assertThat(served.reseal(tenant, "BG-NONE").status).isEqualTo(404)
        }
}
