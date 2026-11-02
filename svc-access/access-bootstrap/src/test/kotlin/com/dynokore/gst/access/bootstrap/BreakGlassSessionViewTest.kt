package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.persistence.JooqBreakGlassSessionView
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionReplica
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-9383, session view (INV-Q-9298 B): `restrictedSession` answers from access-svc's own table
 * `access.break_glass_session` under row-level security, by return value. No replica is a constructor parameter.
 */
class BreakGlassSessionViewTest {
    private val at = Instant.parse("2026-11-02T10:00:00Z")

    private fun insert(
        tenant: TenantId,
        ref: String,
        expires: Instant,
        status: String,
        approvedBy: List<String>,
    ) = BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "insert into access.break_glass_session (id, tenant_id, unlock_ref, session_hours, unlocked_at, " +
                    "expires_at, approved_by, status, created_at, created_by, updated_at, updated_by, version) " +
                    "values (?::uuid, ?::uuid, ?, 4, ?::timestamptz - interval '4 hours', ?::timestamptz, ?, ?, " +
                    "now(), 'seed', now(), 'seed', 0)",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, tenant.toString())
                it.setString(3, ref)
                it.setString(4, expires.toString())
                it.setString(5, expires.toString())
                it.setArray(6, c.createArrayOf("text", approvedBy.toTypedArray()))
                it.setString(7, status)
                it.executeUpdate()
            }
    }

    private fun <T> withView(block: (BreakGlassSessionView, TenantContext) -> T): T {
        var result: T? = null
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            result =
                block(context.getBean(BreakGlassSessionView::class.java), context.getBean(TenantContext::class.java))
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun `INV-AC-9383 the bean is the table view and no constructor parameter is a replica`() {
        val types = JooqBreakGlassSessionView::class.java.constructors.flatMap { it.parameterTypes.toList() }
        assertThat(types).isNotEmpty()
        assertThat(types).doesNotContain(AuthorityReplica::class.java, BreakGlassSessionReplica::class.java)
        types.forEach { assertThat(AuthorityReplica::class.java.isAssignableFrom(it)).isFalse() }
        withView { view, _ -> assertThat(view).isInstanceOf(JooqBreakGlassSessionView::class.java) }
    }

    @Test
    fun `INV-AC-9383 the view answers the matching open session of the tenant and null otherwise`(): Unit =
        withView { view, tenants ->
            val tenant = TenantId(UUID.randomUUID())
            val other = TenantId(UUID.randomUUID())
            val e = at.plusSeconds(3600)
            insert(tenant, "BG-OPEN", e, "UNLOCKED", listOf("p1", "p2"))
            insert(tenant, "BG-SEALED", e.plusSeconds(10), "SEALED", listOf("p1", "p2"))
            insert(tenant, "BG-OLD", at, "UNLOCKED", listOf("p1", "p0"))
            insert(other, "BG-OTHER", e, "UNLOCKED", listOf("p9", "p8"))

            fun ask(
                t: TenantId,
                actor: String,
                instant: Instant = at,
                bound: TenantId = t,
            ) = tenants.bind(bound, "view-test") { view.restrictedSession(t, actor, instant) }

            val found = checkNotNull(ask(tenant, "p1"))
            assertThat(found.unlockRef).isEqualTo("BG-OPEN")
            assertThat(found.expiresAt).isEqualTo(e)
            assertThat(found.approvedBy).containsExactly("p1", "p2")
            assertThat(found.tenant).isEqualTo(tenant)
            assertThat(ask(tenant, "p2")?.unlockRef).isEqualTo("BG-OPEN")
            // not an approver, and look-alikes of an approver (string equality)
            listOf("p3", "P1", "p1 ", "p", "p1,p2", "").forEach {
                assertThat(ask(tenant, it)).describedAs("actor '$it'").isNull()
            }
            // the expiry is strict: at E the session is over, one second before it is open
            assertThat(ask(tenant, "p1", e)).isNull()
            assertThat(ask(tenant, "p1", e.minusSeconds(1))?.unlockRef).isEqualTo("BG-OPEN")
            // another tenant's approver and another tenant's row
            assertThat(ask(other, "p1")).isNull()
            assertThat(ask(tenant, "p9")).isNull()
            assertThat(ask(other, "p9")?.unlockRef).isEqualTo("BG-OTHER")
            // a tenant other than the bound one reads nothing (row-level security and the bound check)
            assertThat(ask(tenant, "p1", bound = other)).isNull()
            assertThat(ask(other, "p9", bound = tenant)).isNull()
        }

    @Test
    fun `INV-AC-9383 several rows give the latest expiry and a tie gives the smaller unlock ref`(): Unit =
        withView { view, tenants ->
            val tenant = TenantId(UUID.randomUUID())
            val late = at.plusSeconds(7200)
            insert(tenant, "BG-B", late, "UNLOCKED", listOf("p1", "p0"))
            insert(tenant, "BG-A", late, "UNLOCKED", listOf("p1", "p0"))
            insert(tenant, "BG-C", at.plusSeconds(60), "UNLOCKED", listOf("p1", "p0"))
            assertThat(tenants.bind(tenant, "t") { view.restrictedSession(tenant, "p1", at) }?.unlockRef)
                .isEqualTo("BG-A")
            insert(tenant, "BG-Z", late.plusSeconds(1), "UNLOCKED", listOf("p1", "p0"))
            assertThat(tenants.bind(tenant, "t") { view.restrictedSession(tenant, "p1", at) }?.unlockRef)
                .isEqualTo("BG-Z")
        }
}
