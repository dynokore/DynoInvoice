package com.dynokore.gst.gatewayauthz.check

import com.dynokore.gst.gatewayauthz.MutableClock
import com.dynokore.gst.gatewayauthz.ratelimit.ProtectiveRateLimiter
import com.dynokore.gst.gatewayauthz.ratelimit.RateLimitSettings
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialRefresher
import com.dynokore.gst.gatewayauthz.refresh.StubAccessSvc
import com.dynokore.gst.gatewayauthz.revocation.Entries
import com.dynokore.gst.gatewayauthz.token.AccessTokenVerifier
import com.dynokore.gst.gatewayauthz.token.TestTokens
import com.dynokore.gst.gatewayauthz.token.TokenSettings
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

class ExtAuthorizerTest {
    private val clock = MutableClock()
    private val registry = SimpleMeterRegistry()
    private val tokens = TestTokens()
    private val accessSvc = StubAccessSvc().apply { keys = listOf(tokens.verificationKey) }
    private val cache = KeyMaterialCache()
    private val refresher = KeyMaterialRefresher(accessSvc, cache, clock)
    private val authorizer =
        ExtAuthorizer(
            cache,
            AccessTokenVerifier(TokenSettings(TestTokens.ISSUER, TestTokens.AUDIENCE), { cache.current().keys }, clock),
            ProtectiveRateLimiter(RateLimitSettings(signedLinkPerLink = 2), readyReplicas = 1, clock = clock),
            listOf("x-workload-token", "x-deadline-ms"),
            RouteConfigurationFaults(registry),
        )

    private fun machine(token: String?) =
        AuthzRequest(
            headers = listOfNotNull(token?.let { "authorization" to "Bearer $it" }).toMap() + ("traceparent" to TRACE),
            path = "/v1/invoices",
            context = mapOf("route-class" to "machine-api", "required-scope" to "ingest"),
        )

    private fun loaded() = refresher.refreshOnce()

    @Test
    fun `a valid token is allowed, the JWT passes unchanged, identity headers are removed and fields are added`() {
        loaded()
        val decision = authorizer.check(machine(tokens.mint(clock.instant())))
        decision.allowed shouldBe true
        decision.headersToRemove shouldBe listOf("x-workload-token", "x-deadline-ms")
        decision.headersToRemove.contains("authorization") shouldBe false
        decision.headersToRemove.contains("traceparent") shouldBe false
        decision.responseHeaders["RateLimit-Policy"] shouldBe "\"ingest\";q=600;w=60, \"tenant\";q=1200;w=60"
    }

    @Test
    fun `no bearer token or an invalid one on the machine API is 401 SRT-003 in the error envelope`() {
        loaded()
        listOf(machine(null), machine("garbage"), machine(tokens.mint(clock.instant(), issuedAt = null))).forEach {
            val decision = authorizer.check(it)
            decision.allowed shouldBe false
            decision.status shouldBe 401
            decision.code shouldBe "SRT-003"
            decision.body shouldContain "\"code\":\"SRT-003\""
            decision.body shouldContain TRACE
        }
    }

    @Test
    fun `a listed token is refused, and a PRINCIPAL entry refuses by client_id`() {
        loaded()
        val listed = tokens.mint(clock.instant(), jti = "listed")
        val other = tokens.mint(clock.instant(), clientId = "PRN-00099", subject = "PRN-00099")
        clock.advance(Duration.ofSeconds(1))
        accessSvc.revoke(Entries.token("listed", clock.instant(), version = 1))
        accessSvc.revoke(Entries.principal("PRN-00099", clock.instant(), version = 2))
        refresher.refreshOnce()
        authorizer.check(machine(listed)).status shouldBe 401
        authorizer.check(machine(other)).status shouldBe 401
        authorizer.check(machine(tokens.mint(clock.instant()))).allowed shouldBe true
    }

    @Test
    fun `a token of a listed subject issued after revokedAt is allowed and one issued before is refused`() {
        loaded()
        val before = tokens.mint(clock.instant(), clientId = null, subject = "agent-7")
        clock.advance(Duration.ofSeconds(1))
        accessSvc.revoke(Entries.subject("agent-7", clock.instant()))
        refresher.refreshOnce()
        clock.advance(Duration.ofSeconds(1))
        val after = tokens.mint(clock.instant(), clientId = null, subject = "agent-7")
        authorizer.check(machine(before)).status shouldBe 401
        authorizer.check(machine(after)).allowed shouldBe true
    }

    @Test
    fun `a token revoked during the test is refused within one second`() {
        loaded()
        val token = tokens.mint(clock.instant(), jti = "j-live")
        authorizer.check(machine(token)).allowed shouldBe true
        accessSvc.revoke(Entries.token("j-live", clock.instant()))
        clock.advance(Duration.ofMillis(500))
        refresher.refreshOnce() // the background refresh runs at least once a second (INV-Q-3973 A)
        authorizer.check(machine(token)).status shouldBe 401
    }

    @Test
    fun `with access-svc down, machine tokens are served from the cache past the staleness bound`() {
        loaded()
        accessSvc.down = true
        clock.advance(Duration.ofMinutes(5))
        refresher.refreshOnce()
        authorizer.check(machine(tokens.mint(clock.instant()))).allowed shouldBe true
    }

    @Test
    fun `before keys and a snapshot are loaded a bearer request is 503 SRT-011`() {
        val decision = authorizer.check(machine(tokens.mint(clock.instant())))
        decision.status shouldBe 503
        decision.code shouldBe "SRT-011"
    }

    @Test
    fun `a signed-link request without a token is admitted and counted per link`() {
        val template = "/s/{linkRef}"

        fun link(ref: String) =
            AuthzRequest(
                headers = mapOf("x-workload-token" to "forged", "x-deadline-ms" to "1"),
                path = "/s/$ref?p=1",
                context =
                    mapOf("route-class" to "signed-link", "path-template" to template, "link-parameter" to "linkRef"),
            )
        repeat(2) {
            val decision = authorizer.check(link("L-1"))
            decision.allowed shouldBe true
            decision.headersToRemove shouldBe listOf("x-workload-token", "x-deadline-ms")
        }
        val refused = authorizer.check(link("L-1"))
        refused.status shouldBe 429
        refused.code shouldBe "SRT-012"
        refused.responseHeaders["RateLimit-Policy"] shouldBe "\"signed-link\";q=2;w=60"
        refused.responseHeaders["Retry-After"] shouldBe "60"
        refused.responseHeaders["Content-Type"] shouldBe "application/problem+json"
        refused.body shouldContain "\"code\":\"SRT-012\""
        authorizer.check(link("L-2")).allowed shouldBe true
    }

    @Test
    fun `an AP notification without a token is admitted and counted per adapter`() {
        val decision =
            authorizer.check(
                AuthzRequest(
                    headers = emptyMap(),
                    path = "/v1/ap/ap-1/notifications",
                    context = mapOf("route-class" to "ap-notification", "path-template" to AP_TEMPLATE),
                ),
            )
        decision.allowed shouldBe true
        decision.responseHeaders["RateLimit-Policy"] shouldBe "\"ap-callback\";q=600;w=60"
    }

    @Test
    fun `no route class, an unknown one or a path off its template is a configuration fault, 500 SRT-010`() {
        loaded()
        val token = tokens.mint(clock.instant())
        listOf(
            emptyMap(),
            mapOf("route-class" to "other"),
            mapOf("route-class" to "ap-notification", "path-template" to AP_TEMPLATE),
        ).forEach { context ->
            val decision = authorizer.check(machine(token).copy(context = context))
            decision.allowed shouldBe false
            decision.status shouldBe 500
            decision.code shouldBe "SRT-010"
            decision.body shouldNotContain "detail"
        }
        registry.counter(RouteConfigurationFaults.METRIC).count() shouldBe 3.0
    }

    @Test
    fun `a console request without a token is admitted, not counted, and loses the internal-only headers`() {
        val console =
            AuthzRequest(
                headers = mapOf("x-workload-token" to "forged", "x-deadline-ms" to "1", "cookie" to "__Host-s=1"),
                path = "/v1/exceptions",
                context = mapOf("route-class" to "console"),
            )
        repeat(5) {
            val decision = authorizer.check(console)
            decision.allowed shouldBe true
            decision.headersToRemove shouldBe listOf("x-workload-token", "x-deadline-ms")
            decision.responseHeaders shouldBe emptyMap()
        }
    }

    @Test
    fun `a token without exactly one machine scope is 401 SRT-003`() {
        loaded()
        listOf(null, "openid", "ingest reporting").forEach { scope ->
            val decision = authorizer.check(machine(tokens.mint(clock.instant(), scope = scope)))
            decision.status shouldBe 401
            decision.code shouldBe "SRT-003"
        }
    }

    private companion object {
        const val TRACE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        const val AP_TEMPLATE = "/v1/ap/{adapterId}/notifications"
    }
}
