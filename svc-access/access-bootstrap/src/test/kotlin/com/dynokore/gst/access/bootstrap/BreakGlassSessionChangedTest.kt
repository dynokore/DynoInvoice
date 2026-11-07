package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.IdentityProviderAvailability
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.viaConsole
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.SealFixture.activated
import com.dynokore.gst.access.bootstrap.SealFixture.pair
import com.dynokore.gst.access.bootstrap.SealFixture.passExpiry
import com.dynokore.gst.access.bootstrap.SealFixture.reseal
import com.dynokore.gst.access.bootstrap.SealFixture.unlocked
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-9303 (a, b, f; INV-Q-9119 A): an unlock and a reseal each append the platform event `BreakGlassSessionChanged`
 * once, in the command transaction, through the real context over PostgreSQL; a refused reseal and an unlock rolled
 * back after the session row was inserted append none.
 */
class BreakGlassSessionChangedTest {
    private val type = "com.dynokore.gst.platform.BreakGlassSessionChanged.v1"

    /** The `BreakGlassSessionChanged` rows of [tenant]'s outbox as JSON, oldest first, read as the superuser. */
    private fun changes(tenant: TenantId): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement("select data::text from access.outbox where tenant_id = ? and type = ? order by seq")
                .use {
                    it.setObject(1, tenant.value)
                    it.setString(2, type)
                    it.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    @Test
    fun `INV-AC-9303 a b - the unlock appends one OPEN event and the reseal one SEALED event, in order`() =
        withService { served ->
            val tenant = served.activated().tenant
            served.pair(tenant)
            val (ref, expiresAt) = served.unlocked(tenant)
            val open = changes(tenant).single().let { json(it) }
            assertThat(open["unlockRef"].asString()).isEqualTo(ref)
            assertThat(open["tenantRef"].asString()).isEqualTo(tenant.toString())
            assertThat(open["status"].asString()).isEqualTo("OPEN")
            assertThat(Instant.parse(open["expiresAt"].asString())).isEqualTo(expiresAt)
            val approvers = open["approvedBy"].let { a -> (0 until a.size()).map { a[it].asString() } }
            assertThat(approvers).containsExactly(SealFixture.ADMIN, SealFixture.APPROVER)
            assertThat(served.reseal(tenant, ref).status).isEqualTo(200)
            val rows = changes(tenant).map { json(it) }
            assertThat(rows.map { it["status"].asString() }).containsExactly("OPEN", "SEALED")
            val sealed = rows[1]
            assertThat(sealed["unlockRef"].asString()).isEqualTo(ref)
            assertThat(sealed["tenantRef"].asString()).isEqualTo(tenant.toString())
            assertThat(sealed["expiresAt"]).isEqualTo(open["expiresAt"])
            assertThat(sealed["approvedBy"]).isEqualTo(open["approvedBy"])
            // a second reseal answers the data and changes nothing
            assertThat(served.reseal(tenant, ref).status).isEqualTo(200)
            assertThat(changes(tenant)).hasSize(2)
        }

    @Test
    fun `INV-AC-9303 b - a reseal after the expiry is refused and appends nothing`() =
        withService { served ->
            val tenant = served.activated().tenant
            served.pair(tenant)
            val (ref, expiresAt) = served.unlocked(tenant)
            served.passExpiry(expiresAt)
            assertThat(served.reseal(tenant, ref).status).isEqualTo(409)
            assertThat(changes(tenant).map { json(it)["status"].asString() }).containsExactly("OPEN")
        }

    @Test
    fun `INV-AC-9303 f - an unlock rolled back after the session row was inserted appends nothing`() =
        withService { served ->
            val tenant = served.activated().tenant
            served.pair(tenant)
            val caller = SealFixture.ADMIN
            val both = """["$caller","${SealFixture.APPROVER}"]"""
            // no approval was made: the session row is inserted, the approval shortfall rolls the unlock back (403)
            val rolledBack = served.unlockBody(tenant, """{"tenantRef":"$tenant","approvedBy":$both}""")
            assertThat(rolledBack.status).describedAs(rolledBack.contentAsString).isEqualTo(403)
            val alone = served.unlockBody(tenant, """{"tenantRef":"$tenant","approvedBy":["$caller"]}""")
            assertThat(alone.status).describedAs(alone.contentAsString).isGreaterThanOrEqualTo(400)
            assertThat(changes(tenant)).isEmpty()
            assertThat(FirstAdministratorFixture.rows("access.break_glass_session", tenant)).isEmpty()
        }

    private fun ServedContext.unlockBody(
        tenant: TenantId,
        body: String,
    ) = post(
        FirstAdministratorFixture.operation("unlockBreakGlass"),
        viaConsole(AcceptFirstAdministratorFixture.token(SealFixture.ADMIN, tenant)),
        body,
        UUID.randomUUID(),
    )
}

/**
 * INV-AC-9303 (f): the unlock refusals before the session row is written append no `BreakGlassSessionChanged`: the
 * identity-provider probe 409 and an approver who does not hold the pairing role.
 */
class BreakGlassSessionChangedRefusalTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val type = "com.dynokore.gst.platform.BreakGlassSessionChanged.v1"

    private fun run(block: (BreakGlassController, TenantContext, CapturedEvents, StubIdentityProvider) -> Unit) {
        val clock = MovableClock()
        val idp = StubIdentityProvider(clock)
        BoundaryFixture
            .runner()
            .withBean(DirectoryGroupMembers::class.java, { DirectoryGroupMembers { emptySet() } })
            .withAllowBeanDefinitionOverriding(true)
            .withBean("identityProviderAvailability", IdentityProviderAvailability::class.java, { idp.availability })
            .withBean("clock", Clock::class.java, { clock })
            .run { context ->
                context.startupFailure?.let { throw it }
                Seeds.grant(context, tenant, SeedSpec("IDN-ADM", Role.TENANT_ADMIN))
                Seeds.grant(context, tenant, SeedSpec("IDN-APP", Role.TAX_APPROVER))
                Seeds.grant(context, tenant, SeedSpec("IDN-OTHER", Role.AUDITOR))
                val captured = context.getBean(CapturedEvents::class.java)
                captured.envelopes.clear()
                // the approvals are made before the unlock, so the refusal under test is the only one left
                BreakGlassApproving.approveOthers(context, tenant, "IDN-ADM", listOf("IDN-ADM", "IDN-APP", "IDN-OTHER"))
                block(
                    context.getBean(BreakGlassController::class.java),
                    context.getBean(TenantContext::class.java),
                    captured,
                    idp,
                )
            }
    }

    private fun unlock(
        api: BreakGlassController,
        tenants: TenantContext,
        vararg approvers: String,
    ) = tenants.bind(tenant, "IDN-ADM") {
        api.unlockBreakGlass(UUID.randomUUID(), UnlockBreakGlassRequest(tenant.toString(), approvers.toList()))
    }

    private fun sessions(): List<String> = FirstAdministratorFixture.rows("access.break_glass_session", tenant)

    @Test
    fun `INV-AC-9303 f - the identity-provider probe 409 appends nothing and opens no session`() =
        run { api, tenants, captured, idp ->
            idp.tick()
            assertThatThrownBy { unlock(api, tenants, "IDN-ADM", "IDN-APP") }.isInstanceOf(ApiConflict::class.java)
            assertThat(captured.envelopes.filter { it.type == type }).isEmpty()
            assertThat(sessions()).isEmpty()
        }

    @Test
    fun `INV-AC-9303 f - roles not held appends nothing and opens no session`() =
        run { api, tenants, captured, idp ->
            idp.failBoth()
            repeat(3) { idp.tick() }
            assertThatThrownBy { unlock(api, tenants, "IDN-ADM", "IDN-OTHER") }.isInstanceOf(ApiRefusal::class.java)
            assertThat(captured.envelopes.filter { it.type == type }).isEmpty()
            assertThat(sessions()).isEmpty()
        }
}
