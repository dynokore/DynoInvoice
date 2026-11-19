package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.SealFixture.accountRows
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
 * replaces the sealing credential and returns the new one once; only an identity stored as `approvedBy` of the unlock
 * may call it, whatever role it holds (INV-AC-24943, owner ruling 2026-11-18); a reseal after the expiry is 409 with
 * no catalogue code.
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
            // the call of a member of the pair
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
            assertThat(audits.single()).contains(SealFixture.ADMIN)
        }

    private val memberA = "user:eng-1" // platform.engineer: no cell for break-glass.reseal in the pinned catalogue
    private val memberB = "user:op-1" // deployment.operator
    private val outsiderC = "user:op-2" // deployment.operator, not in the pair

    private fun withPairService(block: (ServedContext) -> Unit) =
        withService(
            configure = {
                it.withPropertyValues(
                    "access.installation-roles[2].subject=$outsiderC",
                    "access.installation-roles[2].role=deployment.operator",
                )
            },
            block = block,
        )

    private fun store(
        tenant: TenantId,
        unlockRef: String,
        status: String = "UNLOCKED",
        approvedBy: List<String> = listOf(memberA, memberB),
    ) = BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "insert into access.break_glass_session (id, tenant_id, unlock_ref, session_hours, unlocked_at, " +
                    "expires_at, approved_by, status, created_at, created_by, updated_at, updated_by, version) " +
                    "values (?::uuid, ?::uuid, ?, 4, now(), now() + interval '4 hours', ?, ?, now(), 'seed', now(), " +
                    "'seed', 0)",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, tenant.toString())
                it.setString(3, unlockRef)
                it.setArray(4, c.createArrayOf("text", approvedBy.toTypedArray()))
                it.setString(5, status)
                it.executeUpdate()
            }
    }

    private fun audits(served: ServedContext) = capturedEvents(served).filter { it.contains("resealBreakGlass") }

    private fun refusedAndUnchanged(
        caller: String,
        expected: Int = 403,
    ) = withPairService { served ->
        val tenant = served.activated().tenant
        store(tenant, "BG-7001")
        val verifierBefore = verifier(tenant)
        val statusBefore = sessionStatus(tenant, "BG-7001")
        val auditBefore = audits(served)
        val everything = served.stored()
        val refused = served.reseal(tenant, "BG-7001", caller)
        assertThat(refused.status).describedAs("$caller: ${refused.contentAsString}").isEqualTo(expected)
        assertThat(json(refused).has("sealingCredential")).isFalse()
        assertThat(verifier(tenant)).isEqualTo(verifierBefore)
        assertThat(sessionStatus(tenant, "BG-7001")).isEqualTo(statusBefore)
        assertThat(audits(served)).isEqualTo(auditBefore)
        assertThat(served.stored()).isEqualTo(everything)
    }

    @Test
    fun `INV-AC-24943 member A, with no cell for break-glass-reseal, is 200 and gets the credential once`() =
        withPairService { served ->
            val tenant = served.activated().tenant
            store(tenant, "BG-7001")
            val before = verifier(tenant)
            val first = served.reseal(tenant, "BG-7001", memberA)
            assertThat(first.status).describedAs(first.contentAsString).isEqualTo(200)
            val rotated = json(first)["sealingCredential"].asString()
            assertThat(verifier(tenant)).isNotEqualTo(before).isEqualTo(digestOf(rotated))
            assertThat(sessionStatus(tenant, "BG-7001")).isEqualTo("SEALED")
            val audit = audits(served)
            assertThat(audit).hasSize(1)
            assertThat(audit.single()).contains(memberA)
            // the other member's later call is 200 without the property and changes nothing
            val second = served.reseal(tenant, "BG-7001", memberB)
            assertThat(second.status).describedAs(second.contentAsString).isEqualTo(200)
            assertThat(json(second).has("sealingCredential")).isFalse()
            assertThat(verifier(tenant)).isEqualTo(digestOf(rotated))
            assertThat(audits(served)).hasSize(1)
        }

    @Test
    fun `INV-AC-24943 member B, a deployment operator, is 200 on a second unlock and gets the credential`() =
        withPairService { served ->
            val tenant = served.activated().tenant
            store(tenant, "BG-7001")
            store(tenant, "BG-7002")
            assertThat(served.reseal(tenant, "BG-7001", memberA).status).isEqualTo(200)
            val before = verifier(tenant)
            val response = served.reseal(tenant, "BG-7002", memberB)
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            val rotated = json(response)["sealingCredential"].asString()
            assertThat(verifier(tenant)).isNotEqualTo(before).isEqualTo(digestOf(rotated))
            assertThat(sessionStatus(tenant, "BG-7002")).isEqualTo("SEALED")
            assertThat(audits(served).last()).contains(memberB)
        }

    @Test
    fun `INV-AC-24943 non-member C, a deployment operator, is 403 and verifier, status and audit are unchanged`() =
        refusedAndUnchanged(outsiderC)

    @Test
    fun `INV-AC-24943 look-alike of A with a trailing space is 403 and nothing changes`() =
        refusedAndUnchanged("$memberA ")

    @Test
    fun `INV-AC-24943 look-alike of A in upper case is 403 and nothing changes`() =
        refusedAndUnchanged(memberA.uppercase())

    @Test
    fun `INV-AC-24943 look-alike of A as a prefix of the stored value is 403 and nothing changes`() =
        refusedAndUnchanged(memberA.dropLast(1))

    @Test
    fun `INV-AC-24943 an unknown reference is 404 for A`() =
        withPairService { served ->
            val tenant = served.activated().tenant
            val verifierBefore = verifier(tenant)
            assertThat(served.reseal(tenant, "BG-9999", memberA).status).isEqualTo(404)
            assertThat(verifier(tenant)).isEqualTo(verifierBefore)
        }

    @Test
    fun `INV-AC-24943 an unlock of another tenant is 404 for A and nothing changes`() =
        withPairService { served ->
            val own = served.activated().tenant
            val other = served.activated().tenant
            store(other, "BG-7001")
            val everything = served.stored()
            assertThat(served.reseal(own, "BG-7001", memberA).status).isEqualTo(404)
            assertThat(served.stored()).isEqualTo(everything)
            assertThat(sessionStatus(other, "BG-7001")).isEqualTo("UNLOCKED")
        }

    @Test
    fun `INV-AC-24943 a sealed unlock is 403 for a non-member and 200 without the property for a member`() =
        withPairService { served ->
            val tenant = served.activated().tenant
            store(tenant, "BG-7001", status = "SEALED")
            val everything = served.stored()
            assertThat(served.reseal(tenant, "BG-7001", outsiderC).status).isEqualTo(403)
            assertThat(served.stored()).isEqualTo(everything)
            val member = served.reseal(tenant, "BG-7001", memberA)
            assertThat(member.status).describedAs(member.contentAsString).isEqualTo(200)
            assertThat(json(member).has("sealingCredential")).isFalse()
            assertThat(served.stored()).isEqualTo(everything)
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

    @Test
    fun `INV-AC-8852 a reseal of an open unlock with no account row closes it and returns no credential`() =
        withService { served ->
            // never activated: a pair and an open unlock but no sealed-account row (INV-Q-16102 A)
            val tenant = newTenant()
            served.pair(tenant)
            val (ref, _) = served.unlocked(tenant)
            assertThat(accountRows(tenant)).isEmpty()
            val auditBefore = capturedEvents(served).filter { it.contains("resealBreakGlass") }
            val response = served.reseal(tenant, ref)
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            assertThat(json(response).has("sealingCredential")).isFalse()
            assertThat(accountRows(tenant)).isEmpty()
            assertThat(sessionStatus(tenant, ref)).isEqualTo("SEALED")
            // one audit entry, under the acting user and never a role
            val audits = capturedEvents(served).filter { it.contains("resealBreakGlass") }
            assertThat(audits).hasSize(auditBefore.size + 1)
            assertThat(audits.last()).contains(SealFixture.ADMIN)
            // sign-in and recording on that unlock: 409 and no catalogue code
            assertConflictWithoutCode(served.signIn(tenant, ref, "any-credential"))
            val refusal = catchThrowable { served.record(tenant, ref) }
            assertThat(refusal).isInstanceOf(ApiConflict::class.java)
            assertThat(ApiRefusalAdvice().conflict(refusal as ApiConflict).body).doesNotContain("\"code\"")
        }
}
