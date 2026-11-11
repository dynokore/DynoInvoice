package com.dynokore.gst.consoleBff.adapters.authz

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.authz.ReadPermissions
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** INV-AC-20309: a read is authorised by permission key through the authority replica; unmapped reads are refused. */
class ConsoleReadAuthorisationFilterTest {
    private class FixedClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-10T10:00:00Z")
    }

    private val clock = FixedClock()
    private val tenant = TenantId.of("11111111-1111-1111-1111-111111111111")
    private val roles = OperationRoleMap { key -> if (key == "exception.view") mapOf(FIXTURE_ROLE to CellAllowance.PERMITTED) else emptyMap() }
    private val replica = AuthorityReplica(clock, roles)
    private val production = CatalogueReadPermissions()
    private val filter = ConsoleReadAuthorisationFilter(production, replica, clock)

    private class Outcome(
        val response: MockHttpServletResponse,
        val chain: MockFilterChain,
    ) {
        val called get() = chain.request != null
    }

    private fun run(
        method: String = "GET",
        uri: String = "/v1/exceptions",
        principal: String? = "p1",
        subject: ConsoleReadAuthorisationFilter = filter,
    ): Outcome {
        val request = MockHttpServletRequest(method, uri).apply { requestURI = uri }
        if (principal != null) {
            request.setAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, principal)
            request.setAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE, tenant.toString())
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        subject.doFilter(request, response, chain)
        return Outcome(response, chain)
    }

    private fun caughtUp() = replica.onSnapshotComplete(1)

    private fun grant(version: Long) =
        replica.accept(
            AuthorityChanged(
                tenant,
                "p1",
                version,
                AuthorityRecord.RoleGrantRecord(
                    "g1",
                    RoleGrantState.ACTIVE,
                    RoleGrant(FIXTURE_ROLE, GrantScope.TENANT, tenant.toString(), clock.instant().minusSeconds(60)),
                ),
            ),
        )

    private fun revoke(version: Long) =
        replica.accept(AuthorityChanged(tenant, "p1", version, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)))

    @Test
    fun `INV-AC-20309 an allowed read passes and the chain is called once`() {
        caughtUp()
        grant(2)
        val out = run()
        out.response.status shouldBe 200
        out.called shouldBe true
        run(method = "HEAD").called shouldBe true
    }

    @Test
    fun `INV-AC-20309 a denied read is 403 and the chain is not called`() {
        caughtUp()
        val out = run()
        out.response.status shouldBe 403
        out.response.contentType shouldContain "application/problem+json"
        out.response.contentAsString shouldContain "SRT-004"
        out.called shouldBe false
    }

    @Test
    fun `INV-AC-20309 a read that passed before a revocation is refused on the next request`() {
        caughtUp()
        grant(2)
        run().called shouldBe true
        revoke(3)
        val out = run()
        out.response.status shouldBe 403
        out.called shouldBe false
    }

    @Test
    fun `INV-AC-20309 a replica beyond its lag bound never turns into an allow`() {
        val out = run()
        out.response.status shouldBe 403
        out.called shouldBe false
    }

    @Test
    fun `INV-AC-20309 a GET route with no permission key is refused and the chain is not called`() {
        caughtUp()
        grant(2)
        val unmapped = ConsoleReadAuthorisationFilter({ _, _ -> null }, replica, clock)
        val out = run(subject = unmapped)
        out.response.status shouldBe 403
        out.called shouldBe false
    }

    @Test
    fun `INV-AC-20309 a request without a principal is refused 401`() {
        caughtUp()
        grant(2)
        val out = run(principal = null)
        out.response.status shouldBe 401
        out.called shouldBe false
    }

    @Test
    fun `INV-AC-20309 CatalogueReadPermissions conforms to ReadPermissions`() {
        val p: ReadPermissions = CatalogueReadPermissions()
        p.keyFor("GET", "/v1/exceptions") shouldBe "exception.view"
    }

    @Test
    fun `INV-AC-20309 POST on the same path is not decided by this filter and is passed on`() {
        caughtUp()
        run(method = "POST").called shouldBe true
    }

    @Test
    fun `INV-AC-20309 a trailing segment and a near-miss path are unmapped and refused`() {
        caughtUp()
        grant(2)
        for (uri in listOf("/v1/exceptions/extra", "/v1/exception", "/v1/exceptions/", "/V1/exceptions")) {
            production.keyFor("GET", uri) shouldBe null
            val out = run(uri = uri)
            out.response.status shouldBe 403
            out.called shouldBe false
        }
        production.keyFor("POST", "/v1/exceptions") shouldBe null
    }

    @Test
    fun `INV-AC-20309 a refusal body contains no role name`() {
        caughtUp()
        val out = run()
        out.response.contentAsString shouldNotContain FIXTURE_ROLE
    }

    private companion object {
        const val FIXTURE_ROLE = "fixture-holder"
    }
}
