package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.BreakGlassAlertOutbox
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-AC-9302 (INV-Q-9109 A): each approver other than the caller approves through their own session, within 15
 * minutes, and the unlock consumes the approval. Over the real database with a test clock; statuses, session rows,
 * approval rows, audit entries and alerts are compared by equality.
 */
class BreakGlassApprovalTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val other = TenantId(UUID.randomUUID())
    private val p1 = "identity:USR-001"
    private val p2 = "identity:USR-009"
    private val p3 = "identity:USR-003"

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val api = context.getBean(BreakGlassController::class.java)
        val clock = context.getBean(java.time.Clock::class.java) as MovableClock
        val t0: Instant = clock.freeze()

        init {
            listOf(tenant, other).forEach {
                Seeds.grant(context, it, SeedSpec(p1, Role.TENANT_ADMIN))
                Seeds.grant(context, it, SeedSpec(p2, Role.TAX_APPROVER))
                Seeds.grant(context, it, SeedSpec(p3, Role.AUDITOR))
                Seeds.grant(context, it, SeedSpec("identity:USR-0091", Role.TAX_APPROVER))
            }
        }

        /** The status of an approval by [who] for [requested] in [bound]; 403 when refused. */
        fun approve(
            who: String,
            bound: TenantId = tenant,
            requested: String = bound.toString(),
        ): Int =
            try {
                BreakGlassApproving.approve(context, bound, who, requested).statusCode.value()
            } catch (e: ApiRefusal) {
                assertThat(e.message).contains("SRT-004")
                403
            }

        /** The status of an unlock by [caller] in [bound] naming [named]; 403 when refused. */
        fun unlock(
            caller: String,
            vararg named: String,
            bound: TenantId = tenant,
        ): Int =
            try {
                tenants
                    .bind(bound, caller) {
                        val request = UnlockBreakGlassRequest(bound.toString(), named.toList())
                        api.unlockBreakGlass(UUID.randomUUID(), request)
                    }.statusCode
                    .value()
            } catch (e: ApiRefusal) {
                assertThat(e.message).contains("SRT-004")
                403
            }

        fun sessions(bound: TenantId = tenant): Int = count("break_glass_session", bound)

        fun approvals(bound: TenantId = tenant): List<Triple<String, Boolean, String?>> =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement(
                        "SELECT approver_ref, consumed_at IS NOT NULL, consumed_unlock_ref " +
                            "FROM access.break_glass_approval WHERE tenant_id = ?::uuid ORDER BY approver_ref, made_at",
                    ).use { statement ->
                        statement.setString(1, bound.toString())
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(
                                        Triple(rows.getString(1), rows.getBoolean(2), rows.getString(3)),
                                    )
                                }
                            }
                        }
                    }
            }

        fun audits(action: String): Int =
            outbox("type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1' AND data->>'action' = '$action'")

        fun alerts(): Int = outbox("type = '${BreakGlassAlertOutbox.EVENT_TYPE}'")

        private fun outbox(where: String): Int =
            BoundaryFixture.asSuperuser { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT count(*) FROM access.outbox WHERE tenant_id = '$tenant' AND $where")
                        .use { rows -> rows.next().let { rows.getInt(1) } }
                }
            }

        private fun count(
            table: String,
            bound: TenantId,
        ): Int =
            BoundaryFixture.asSuperuser { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT count(*) FROM access.$table WHERE tenant_id = '$bound'")
                        .use { rows -> rows.next().let { rows.getInt(1) } }
                }
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    @Test
    fun `INV-AC-9302 a - an unlock while the other approver has made no approval is 403 and writes nothing`() =
        run { env ->
            assertThat(env.unlock(p1, p1, p2)).isEqualTo(403)
            assertThat(env.sessions()).isZero()
            assertThat(env.approvals()).isEmpty()
            assertThat(env.audits("unlockBreakGlass")).isZero()
            assertThat(env.alerts()).isZero()
        }

    @Test
    fun `INV-AC-9302 b c - 14m59s after the approval the unlock is 201 and consumes it, a second is 403`() =
        run { env ->
            val approved = BreakGlassApproving.approve(env.context, tenant, p2)
            assertThat(approved.statusCode.value()).isEqualTo(201)
            val body = approved.body!!
            assertThat(body.approvalRef).startsWith("BGP-")
            assertThat(body.tenantRef).isEqualTo(tenant.toString())
            assertThat(body.madeAt.toInstant()).isEqualTo(env.t0)
            assertThat(body.expiresAt.toInstant()).isEqualTo(env.t0.plus(Duration.ofMinutes(15)))
            assertThat(env.audits("approveBreakGlassUnlock")).isEqualTo(1)
            assertThat(env.alerts()).isZero()
            env.clock.advance(Duration.ofSeconds(14 * 60 + 59))
            assertThat(env.unlock(p1, p1, p2)).isEqualTo(201)
            assertThat(env.sessions()).isEqualTo(1)
            val ref = env.approvals().single().third
            assertThat(env.approvals()).containsExactly(Triple(p2, true, ref))
            assertThat(ref).startsWith("BG-")
            assertThat(env.audits("unlockBreakGlass")).isEqualTo(1)
            assertThat(env.alerts()).isEqualTo(1)
            // (c) at once again: the approval is consumed
            assertThat(env.unlock(p1, p1, p2)).isEqualTo(403)
            assertThat(env.sessions()).isEqualTo(1)
            assertThat(env.audits("unlockBreakGlass")).isEqualTo(1)
            assertThat(env.alerts()).isEqualTo(1)
            assertThat(env.approvals()).containsExactly(Triple(p2, true, ref))
        }

    @Test
    fun `INV-AC-9302 d - an approval made 15 minutes earlier to the second does not count`() =
        run { env ->
            assertThat(env.approve(p2)).isEqualTo(201)
            env.clock.advance(Duration.ofMinutes(15))
            assertThat(env.unlock(p1, p1, p2)).isEqualTo(403)
            assertThat(env.sessions()).isZero()
            assertThat(env.approvals()).containsExactly(Triple(p2, false, null))
            assertThat(env.audits("unlockBreakGlass")).isZero()
            assertThat(env.alerts()).isZero()
        }

    @Test
    fun `INV-AC-9302 e - a member who holds neither role of the pairing cannot approve and no row is stored`() =
        run { env ->
            assertThat(env.approve(p3)).isEqualTo(403)
            assertThat(env.approvals()).isEmpty()
            assertThat(env.audits("approveBreakGlassUnlock")).isZero()
            // a request that names another tenant is refused too
            assertThat(env.approve(p2, requested = other.toString())).isEqualTo(403)
            assertThat(env.approvals()).isEmpty()
        }

    @Test
    fun `INV-AC-9302 f - two unlocks at the same time on one approval give one 201 and one 403`() =
        run { env ->
            assertThat(env.approve(p2)).isEqualTo(201)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val start = CountDownLatch(1)
                val answers =
                    List(2) {
                        pool.submit<Int> {
                            start.await()
                            env.unlock(p1, p1, p2)
                        }
                    }
                start.countDown()
                assertThat(answers.map { it.get(30, TimeUnit.SECONDS) }.sorted()).containsExactly(201, 403)
            } finally {
                pool.shutdownNow()
            }
            assertThat(env.sessions()).isEqualTo(1)
            assertThat(env.approvals().map { it.second }).containsExactly(true)
            assertThat(env.audits("unlockBreakGlass")).isEqualTo(1)
            assertThat(env.alerts()).isEqualTo(1)
        }

    @Test
    fun `INV-AC-9302 g - an approval of tenant T is not used in T2 and stays untouched`() =
        run { env ->
            assertThat(env.approve(p2)).isEqualTo(201)
            assertThat(env.unlock(p1, p1, p2, bound = other)).isEqualTo(403)
            assertThat(env.sessions(other)).isZero()
            assertThat(env.approvals(other)).isEmpty()
            assertThat(env.approvals()).containsExactly(Triple(p2, false, null))
        }

    @Test
    fun `look-alike - the approval of USR-009 is not used for USR-0091`() =
        run { env ->
            assertThat(env.approve(p2)).isEqualTo(201)
            assertThat(env.unlock(p1, p1, "identity:USR-0091")).isEqualTo(403)
            assertThat(env.sessions()).isZero()
            assertThat(env.approvals()).containsExactly(Triple(p2, false, null))
        }

    @Test
    fun `a new approval replaces the unconsumed one and a refusal before the approvals consumes none`() =
        run { env ->
            assertThat(env.approve(p2)).isEqualTo(201)
            env.clock.advance(Duration.ofMinutes(10))
            assertThat(env.approve(p2)).isEqualTo(201)
            assertThat(env.approvals()).containsExactly(Triple(p2, false, null))
            // one person alone and a pair that does not hold the roles are refused before any approval is consumed
            assertThat(env.unlock(p1, p3, p2)).isEqualTo(403)
            assertThat(env.approvals()).containsExactly(Triple(p2, false, null))
            // ten minutes after the second approval, which is 20 after the first, the unlock works
            env.clock.advance(Duration.ofMinutes(10))
            assertThat(env.unlock(p1, p1, p2)).isEqualTo(201)
            assertThat(env.approvals().single().second).isTrue()
        }
}
