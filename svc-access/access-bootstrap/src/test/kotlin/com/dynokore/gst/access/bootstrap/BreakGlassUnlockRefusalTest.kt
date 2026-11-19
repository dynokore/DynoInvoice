package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.movableClock
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.token
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.viaConsole
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.operation
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.SealFixture.pair
import com.dynokore.gst.access.bootstrap.SealFixture.passExpiry
import com.dynokore.gst.access.bootstrap.SealFixture.passTo
import com.dynokore.gst.access.bootstrap.SealFixture.reseal
import com.dynokore.gst.access.bootstrap.SealFixture.unlocked
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `unlockBreakGlass` is refused with 409 while the latest unlock of the tenant is not SEALED (INV-AC-24945, owner
 * ruling 2026-11-18 A (3)); the refusal stores nothing and consumes no approval. Through the real context over
 * PostgreSQL.
 */
class BreakGlassUnlockRefusalTest {
    private fun ServedContext.approveBoth(tenant: TenantId) = approve(tenant, SealFixture.ADMIN, SealFixture.APPROVER)

    private fun ServedContext.approve(
        tenant: TenantId,
        vararg approvers: String,
    ) {
        approvers.forEach { approver ->
            val approved =
                post(
                    operation("approveBreakGlassUnlock"),
                    viaConsole(token(approver, tenant)),
                    """{"tenantRef":"$tenant"}""",
                    UUID.randomUUID(),
                )
            check(approved.status == 201) { "the approval answered ${approved.status}: ${approved.contentAsString}" }
        }
    }

    private fun ServedContext.tryUnlock(
        tenant: TenantId,
        caller: String = SealFixture.ADMIN,
        other: String = SealFixture.APPROVER,
    ): MockHttpServletResponse =
        post(
            operation("unlockBreakGlass"),
            viaConsole(token(caller, tenant)),
            """{"tenantRef":"$tenant","approvedBy":["$caller","$other"]}""",
            UUID.randomUUID(),
        )

    /** Tries the unlock with no valid approval of the second approver: 403, not 409, and nothing is written. */
    private fun ServedContext.assertDeniedNotRefused(tenant: TenantId) {
        val everything = stored()
        val response = tryUnlock(tenant)
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(403)
        assertThat(stored()).isEqualTo(everything)
    }

    private fun sessions(tenant: TenantId) = rows("access.break_glass_session", tenant)

    private fun unconsumed(tenant: TenantId) =
        rows("access.break_glass_approval", tenant).count { json(it)["consumed_at"].isNull }

    /** Approves for both, tries the unlock and expects 409 with no code and every table and event unchanged. */
    private fun ServedContext.assertRefused(tenant: TenantId) {
        approveBoth(tenant)
        val everything = stored()
        val sessionsBefore = sessions(tenant)
        val approvalsBefore = unconsumed(tenant)
        assertThat(approvalsBefore).isEqualTo(2)
        val response = tryUnlock(tenant)
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(409)
        assertThat(json(response).has("code")).isFalse()
        assertThat(json(response)["detail"].asString()).isNotBlank()
        assertThat(sessions(tenant)).isEqualTo(sessionsBefore)
        assertThat(unconsumed(tenant)).isEqualTo(approvalsBefore)
        assertThat(stored()).isEqualTo(everything)
    }

    private fun seed(
        tenant: TenantId,
        unlockRef: String,
        unlockedAt: String,
        status: String,
    ) = BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "insert into access.break_glass_session (id, tenant_id, unlock_ref, session_hours, unlocked_at, " +
                    "expires_at, approved_by, status, created_at, created_by, updated_at, updated_by, version) " +
                    "values (?::uuid, ?::uuid, ?, 4, ?::timestamptz, ?::timestamptz + interval '4 hours', ?, ?, " +
                    "now(), 'seed', now(), 'seed', 0)",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, tenant.toString())
                it.setString(3, unlockRef)
                it.setString(4, unlockedAt)
                it.setString(5, unlockedAt)
                it.setArray(6, c.createArrayOf("text", arrayOf(SealFixture.ADMIN, SealFixture.APPROVER)))
                it.setString(7, status)
                it.executeUpdate()
            }
    }

    @Test
    fun `INV-AC-24945 a tenant whose latest unlock is open is refused with 409 and nothing changes`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            served.unlocked(tenant)
            served.assertRefused(tenant)
        }

    @Test
    fun `INV-AC-24945 a tenant whose latest unlock is past expiresAt and not sealed is refused with 409`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            val (_, expiresAt) = served.unlocked(tenant)
            served.passExpiry(expiresAt)
            served.assertRefused(tenant)
        }

    @Test
    fun `INV-AC-24945 a tenant whose latest unlock is sealed and one with no unlock answer 201`() =
        withService { served ->
            val sealed = newTenant()
            served.pair(sealed)
            val (ref, _) = served.unlocked(sealed)
            assertThat(served.reseal(sealed, ref).status).isEqualTo(200)
            served.approveBoth(sealed)
            assertThat(served.tryUnlock(sealed).status).isEqualTo(201)
            assertThat(sessions(sealed)).hasSize(2)
            // the unlock consumes the approval of the other approver only, never the caller's own
            assertThat(unconsumed(sealed)).isEqualTo(1)
            val fresh = newTenant()
            served.pair(fresh)
            served.approveBoth(fresh)
            assertThat(served.tryUnlock(fresh).status).isEqualTo(201)
            assertThat(sessions(fresh)).hasSize(1)
        }

    @Test
    fun `INV-AC-24945 after a member reseals the lapsed unlock the next unlock answers 201`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            val (ref, expiresAt) = served.unlocked(tenant)
            served.passExpiry(expiresAt)
            served.assertRefused(tenant)
            assertThat(served.reseal(tenant, ref).status).isEqualTo(200)
            assertThat(served.tryUnlock(tenant).status).isEqualTo(201)
            assertThat(sessions(tenant)).hasSize(2)
        }

    @Test
    fun `INV-AC-24945 a look-alike tenant's open unlock does not refuse the first tenant`() =
        withService { served ->
            val first = newTenant()
            val other = newTenant()
            served.pair(first)
            served.pair(other)
            served.unlocked(other)
            served.approveBoth(first)
            assertThat(served.tryUnlock(first).status).isEqualTo(201)
            // and the other tenant stays refused
            served.assertRefused(other)
        }

    @Test
    fun `INV-AC-24945 the latest unlock is the greatest unlockedAt, not the greatest reference`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            // an older unlock with the greater reference is open; the newer one with the lesser reference is sealed
            seed(tenant, "BG-ZZZZZZZZ", "2026-01-01T00:00:00Z", "UNLOCKED")
            seed(tenant, "BG-AAAAAAAA", "2026-01-02T00:00:00Z", "SEALED")
            served.approveBoth(tenant)
            assertThat(served.tryUnlock(tenant).status).isEqualTo(201)
        }

    @Test
    fun `INV-AC-24945 on a tie of unlockedAt the greater reference is the latest`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            seed(tenant, "BG-AAAAAAAA", "2026-01-02T00:00:00Z", "SEALED")
            seed(tenant, "BG-BBBBBBBB", "2026-01-02T00:00:00Z", "UNLOCKED")
            served.assertRefused(tenant)
        }

    @Test
    fun `INV-AC-24945 an open latest unlock and no approval made is 403 with nothing written`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            served.unlocked(tenant)
            // the approvals of that unlock are consumed: the second approver has none left
            served.assertDeniedNotRefused(tenant)
        }

    @Test
    fun `INV-AC-24945 a lapsed latest unlock and a consumed approval is 403 with nothing written`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            val (_, expiresAt) = served.unlocked(tenant)
            served.passExpiry(expiresAt)
            served.assertDeniedNotRefused(tenant)
        }

    @Test
    fun `INV-AC-24945 an open latest unlock and an approval made 15 minutes earlier is 403 with nothing written`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            served.unlocked(tenant)
            served.approveBoth(tenant)
            served.passTo(served.movableClock().instant().plus(Duration.ofMinutes(15)))
            served.assertDeniedNotRefused(tenant)
        }

    @Test
    fun `INV-AC-24945 an approval made by the caller alone does not stand for the second approver`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            served.unlocked(tenant)
            served.approve(tenant, SealFixture.ADMIN)
            served.assertDeniedNotRefused(tenant)
        }

    @Test
    fun `INV-AC-24945 two simultaneous unlocks of one tenant by two pairs answer one 201 and one 409`() =
        withService { served ->
            val tenant = newTenant()
            served.pair(tenant)
            Seeds.grant(served.application, tenant, SeedSpec(ADMIN_2, Role.TENANT_ADMIN))
            Seeds.grant(served.application, tenant, SeedSpec(APPROVER_2, Role.TAX_APPROVER))
            // each second approver has made an approval, so the 409 is decided by the latest unlock
            served.approve(tenant, SealFixture.APPROVER, APPROVER_2)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val calls =
                    listOf(
                        SealFixture.ADMIN to SealFixture.APPROVER,
                        ADMIN_2 to APPROVER_2,
                    ).map { (caller, other) ->
                        pool.submit<MockHttpServletResponse> {
                            start.await()
                            served.tryUnlock(tenant, caller, other)
                        }
                    }
                start.countDown()
                val answers = calls.map { it.get(60, TimeUnit.SECONDS) }
                assertThat(answers.map { it.status }.sorted())
                    .describedAs(answers.joinToString { it.contentAsString })
                    .containsExactly(201, 409)
                assertThat(sessions(tenant)).hasSize(1)
                assertThat(json(answers.single { it.status == 409 }).has("code")).isFalse()
            } finally {
                pool.shutdownNow()
            }
        }

    private companion object {
        const val ADMIN_2 = "IDN-ADM2"
        const val APPROVER_2 = "IDN-APP2"
    }
}
