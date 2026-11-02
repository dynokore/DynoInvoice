package com.dynokore.gst.access.bootstrap.served

import com.dynokore.gst.access.bootstrap.BoundaryFixture
import com.dynokore.gst.access.bootstrap.MovableClock
import com.dynokore.gst.access.bootstrap.SeedSpec
import com.dynokore.gst.access.bootstrap.Seeds
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-9383 (INV-Q-9298 B, INV-Q-9300 A): over the real database and a test clock, an approver of an open session
 * reaches exactly the five lifecycle operations of the staged contract, the mode writes no break-glass action row and
 * the session grants nothing.
 */
class BreakGlassRestrictionTest {
    private val document = ContractDocument.read()
    private val named = namedCallers(document)
    private val lifecycle =
        setOf(
            "unlockBreakGlass",
            "approveBreakGlassUnlock",
            "signInBreakGlass",
            "resealBreakGlass",
            "recordBreakGlassAction",
        )
    private val p3Token = "Bearer token-p3"
    private val p1OfT2Token = "Bearer token-p1-t2"
    private val unlockRef = "BG-9383"

    @BeforeEach
    fun reset() {
        Doubles.reset()
        deleteSession()
    }

    @AfterEach
    fun clean() {
        deleteSession()
    }

    private fun op(id: String) = document.single { it.operationId == id }

    private fun ServedContext.refused(): Double =
        application
            .getBeansOfType(MeterRegistry::class.java)
            .values
            .sumOf { registry -> registry.find("break_glass_refused_total").counters().sumOf { it.count() } }

    private fun actionRows(): Int =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from access.break_glass_action where unlock_ref = '$unlockRef'").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun insertSession(expires: Instant) =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "insert into access.break_glass_session (id, tenant_id, unlock_ref, session_hours, unlocked_at, " +
                        "expires_at, approved_by, status, created_at, created_by, updated_at, updated_by, version) " +
                        "values (?::uuid, ?::uuid, ?, 4, ?::timestamptz - interval '4 hours', ?::timestamptz, " +
                        "array['user-a','user-p2'], 'UNLOCKED', now(), 'seed', now(), 'seed', 0)",
                ).use {
                    it.setString(1, UUID.randomUUID().toString())
                    it.setString(2, TENANT_A.toString())
                    it.setString(3, unlockRef)
                    it.setString(4, expires.toString())
                    it.setString(5, expires.toString())
                    it.executeUpdate()
                }
        }

    private fun seal() =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "update access.break_glass_session set status = 'SEALED' where unlock_ref = '$unlockRef'",
                )
            }
        }

    private fun deleteSession() =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { it.executeUpdate("delete from access.break_glass_session where unlock_ref = '$unlockRef'") }
        }

    private fun ServedContext.isMapped(operation: ContractOperation) = mappedHandlers().any { it.key == operation.key }

    /** The answer by status and body, the per-request correlation id aside. */
    private fun MockHttpServletResponse.answer(): Pair<Int, String> =
        status to contentAsString.replace(Regex("\"correlationId\":\"[^\"]*\""), "\"correlationId\":\"-\"")

    private fun ServedContext.send(
        operation: ContractOperation,
        token: String?,
        stub: Boolean = false,
    ): MockHttpServletResponse {
        Doubles.principals[p3Token] = EndUserPrincipal("user-p3", TENANT_A, emptySet())
        Doubles.principals[p1OfT2Token] = EndUserPrincipal("user-a", TENANT_B, emptySet())
        return call(operation, workloadToken(allowedCallers(operation, named).first()), token, stubHandler = stub)
    }

    /** True when the mode refused the request: `break_glass_refused_total` rose (the ordinary 403 has its code). */
    private fun ServedContext.refusedByMode(block: () -> MockHttpServletResponse): Boolean {
        val before = refused()
        block()
        return refused() > before
    }

    @Test
    fun `INV-AC-9383 an approver of an open session reaches exactly the five lifecycle operations`() =
        withServedService(named) { served ->
            val clock = served.application.getBean(Clock::class.java) as MovableClock
            val now = clock.freeze()
            val e = now.plus(Duration.ofHours(4))
            insertSession(e)
            val endUser = document.filter { it.callerAuth == "end-user" }
            assertThat(endUser.filter { it.untouched }).describedAs("end-user and untouched").isEmpty()
            assertThat(lifecycle).isSubsetOf(endUser.map { it.operationId }.toSet())
            // the set of (b) is the end-user operations of the staged contract minus the five, by string equality
            val others = endUser.filter { it.operationId !in lifecycle }
            assertThat(others.map { it.key }.toSet())
                .isEqualTo(endUser.map { it.key }.toSet() - lifecycle.map { op(it).key }.toSet())
            assertThat(others).isNotEmpty()
            val rowsBefore = actionRows()

            // (a) the five are not refused by the mode, each reaches its use case once
            lifecycle.forEach { id ->
                served.reset()
                val before = served.refused()
                served.send(op(id), USER_A_TOKEN, stub = served.isMapped(op(id)))
                assertThat(served.refused()).describedAs("$id counter").isEqualTo(before)
                val expectedCalls = if (served.isMapped(op(id))) served.reachedCalls(op(id)) else 0
                assertThat(served.calls).describedAs("$id use case calls").isEqualTo(expectedCalls)
                assertThat(Doubles.reached).describedAs("$id reached").hasSize(expectedCalls)
            }

            // (b) every other end-user operation is 403 SRT-004, its use case not called, the counter rises by one
            others.forEach { o ->
                served.reset()
                val before = served.refused()
                val answer = served.send(o, USER_A_TOKEN, stub = served.isMapped(o))
                assertThat(answer.status).describedAs("${o.key} ${answer.contentAsString}").isEqualTo(403)
                assertThat(answer.contentAsString).describedAs(o.key).contains("SRT-004")
                assertThat(served.calls).describedAs("${o.key} use case calls").isZero()
                assertThat(Doubles.reached).describedAs("${o.key} reached").isEmpty()
                assertThat(served.refused()).describedAs("${o.key} counter").isEqualTo(before + 1)
            }
            assertThat(actionRows()).describedAs("the mode writes no break-glass action row").isEqualTo(rowsBefore)
        }

    @Test
    fun `INV-AC-9383 a non-approver, another tenant, the workload and the signed link are not restricted`() =
        withServedService(named) { served ->
            val clock = served.application.getBean(Clock::class.java) as MovableClock
            val now = clock.freeze()
            val grantRole = op("grantRole")
            val baselines =
                listOf(p3Token, p1OfT2Token).associateWith { served.send(grantRole, it).answer() }
            val workloadOps = listOf("getVerificationKeys", "issueAccessToken", "verifySignatoryLink").map { op(it) }
            val workloadBaseline = workloadOps.associate { it.operationId to served.send(it, null).status }
            insertSession(now.plus(Duration.ofHours(4)))
            val before = served.refused()
            // (c) P3 and P1 of another tenant: the ordinary authorisation answers, unchanged
            baselines.forEach { (token, expected) ->
                val count = served.refused()
                val answer = served.send(grantRole, token)
                assertThat(served.refused()).describedAs(token).isEqualTo(count)
                assertThat(answer.answer()).describedAs(token).isEqualTo(expected)
            }
            // (e) the workload token and the signed link are not touched
            workloadOps.forEach {
                val answer = served.send(it, null)
                assertThat(answer.status)
                    .describedAs(it.operationId)
                    .isEqualTo(workloadBaseline.getValue(it.operationId))
            }
            assertThat(served.refused()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-9383 the restriction ends at the expiry and at the reseal`() =
        withServedService(named) { served ->
            val clock = served.application.getBean(Clock::class.java) as MovableClock
            val now = clock.freeze()
            val e = now.plus(Duration.ofHours(4))
            val grantRole = op("grantRole")
            val baseline = served.send(grantRole, USER_A_TOKEN).answer()
            insertSession(e)
            // (d) one second before E the call is refused
            clock.advance(Duration.between(now, e).minusSeconds(1))
            assertThat(served.refusedByMode { served.send(grantRole, USER_A_TOKEN) }).isTrue()
            // at exactly E it is not restricted
            clock.advance(Duration.ofSeconds(1))
            assertThat(clock.instant()).isEqualTo(e)
            val count = served.refused()
            val atE = served.send(grantRole, USER_A_TOKEN)
            assertThat(served.refused()).isEqualTo(count)
            assertThat(atE.answer()).isEqualTo(baseline)
            // after the reseal, inside the old window, it is not restricted either
            clock.advance(Duration.ofHours(-1))
            assertThat(served.refusedByMode { served.send(grantRole, USER_A_TOKEN) }).isTrue()
            seal()
            val sealedCount = served.refused()
            val sealed = served.send(grantRole, USER_A_TOKEN)
            assertThat(served.refused()).isEqualTo(sealedCount)
            assertThat(sealed.answer()).isEqualTo(baseline)
        }

    @Test
    fun `INV-AC-9383 the look-alikes of the five are refused 403 by the mode, each alone`() =
        withServedService(named) { served ->
            val clock = served.application.getBean(Clock::class.java) as MovableClock
            val workload = workloadToken(named.sorted().first())
            val lookAlikes =
                listOf(
                    "GET" to "/internal/v1/access/break-glass",
                    "POST" to "/internal/v1/access/break-glass/approvals/x",
                    "POST" to "/internal/v1/access/break-glass-actions",
                )
            // no session: the mode does not act, the answer is the one of a request that matches nothing
            lookAlikes.forEach { (method, path) ->
                val before = served.refused()
                served.raw(method, path, workload, USER_A_TOKEN, edge = false)
                assertThat(served.refused()).describedAs("$method $path without a session").isEqualTo(before)
            }
            insertSession(clock.freeze().plus(Duration.ofHours(4)))
            lookAlikes.forEach { (method, path) ->
                // full chain: the sync-edge filter (API-25, order -85) refuses first, 403 SRT-004, no mode count
                served.reset()
                val full = served.raw(method, path, workload, USER_A_TOKEN)
                assertThat(full.status).describedAs("$method $path full chain").isEqualTo(403)
                assertThat(full.contentAsString).describedAs("$method $path full chain").contains("SRT-004")
                // the chain without the sync-edge filter reaches the mode: 403 SRT-004 and the counter rises by one
                val before = served.refused()
                val answer = served.raw(method, path, workload, USER_A_TOKEN, edge = false)
                assertThat(answer.status).describedAs("$method $path mode").isEqualTo(403)
                assertThat(answer.contentAsString).describedAs("$method $path mode").contains("SRT-004")
                assertThat(served.refused()).describedAs("$method $path counter").isEqualTo(before + 1)
            }
            // the exact lifecycle line is not restricted by the mode: the same chain, a path that is one of the five
            val before = served.refused()
            served.raw("POST", "/internal/v1/access/break-glass/approvals", workload, USER_A_TOKEN, edge = false)
            assertThat(served.refused()).describedAs("a lifecycle path").isEqualTo(before)
        }

    @Test
    fun `INV-AC-9383 the session grants nothing - approve without the role answers as without a session`() =
        withServedService(named) { served ->
            val clock = served.application.getBean(Clock::class.java) as MovableClock
            val now = clock.freeze()
            val withdrawn = SeedSpec("user-a", Role.TAX_APPROVER, status = GrantStatus.REVOKED)
            Seeds.grant(served.application, TENANT_A, withdrawn)
            val approve = op("approveBreakGlassUnlock")
            val without = served.send(approve, USER_A_TOKEN)
            insertSession(now.plus(Duration.ofHours(4)))
            val before = served.refused()
            val with = served.send(approve, USER_A_TOKEN)
            assertThat(with.answer()).isEqualTo(without.answer())
            assertThat(served.refused()).isEqualTo(before)
            assertThat(without.status).describedAs("the ordinary decision refuses the withdrawn role").isNotIn(200, 201)
        }
}
