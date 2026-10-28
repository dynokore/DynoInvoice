package com.dynokore.gst.gatewayauthz.check

import com.dynokore.gst.gatewayauthz.MutableClock
import com.dynokore.gst.gatewayauthz.ratelimit.ProtectiveRateLimiter
import com.dynokore.gst.gatewayauthz.ratelimit.RateLimitSettings
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialRefresher
import com.dynokore.gst.gatewayauthz.refresh.StubAccessSvc
import com.dynokore.gst.gatewayauthz.token.AccessTokenVerifier
import com.dynokore.gst.gatewayauthz.token.TestTokens
import com.dynokore.gst.gatewayauthz.token.TokenSettings
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The route-scope check of the machine API (INV-T-9044, INV-Q-9038 A, INV-Q-9032 A). */
class ExtAuthorizerScopeTest {
    private val clock = MutableClock()
    private val registry = SimpleMeterRegistry()
    private val tokens = TestTokens()
    private val accessSvc = StubAccessSvc().apply { keys = listOf(tokens.verificationKey) }
    private val cache = KeyMaterialCache()

    // One counted request per principal and window: a second counted request is 429, so a refusal that reached the
    // limiter would show on the next allowed request.
    private val authorizer =
        ExtAuthorizer(
            cache,
            AccessTokenVerifier(TokenSettings(TestTokens.ISSUER, TestTokens.AUDIENCE), { cache.current().keys }, clock),
            ProtectiveRateLimiter(RateLimitSettings(machinePerPrincipal = 1), readyReplicas = 1, clock = clock),
            listOf("x-workload-token"),
            RouteConfigurationFaults(registry),
        )

    @BeforeEach
    fun load() {
        KeyMaterialRefresher(accessSvc, cache, clock).refreshOnce()
    }

    private fun request(
        bearer: String?,
        context: Map<String, String>,
        routeClass: String = "machine-api",
    ) = AuthzRequest(
        headers = listOfNotNull(bearer?.let { "authorization" to "Bearer $it" }).toMap() + ("traceparent" to TRACE),
        path = "/v1/invoices",
        context = mapOf("route-class" to routeClass) + context,
    )

    private fun mint(
        scope: String,
        environment: String? = null,
        client: String = "PRN-00088",
    ) = tokens.mint(clock.instant(), clientId = client, scope = scope, environment = environment)

    private fun ingest(vararg extra: Pair<String, String>) = mapOf("required-scope" to "ingest") + extra

    private fun refused(
        decision: AuthzDecision,
        status: Int,
        code: String,
    ) {
        decision.allowed shouldBe false
        decision.status shouldBe status
        decision.code shouldBe code
        decision.body shouldNotContain "detail"
    }

    private fun faults() = registry.counter(RouteConfigurationFaults.METRIC).count()

    /** The refusals before must not have used the principal's one request in the window. */
    private fun assertNotCounted() {
        val ok = authorizer.check(request(mint("ingest"), ingest()))
        ok.allowed shouldBe true
        ok.status shouldBe 200
    }

    @Test
    fun `equal scope is allowed and reaches the limiter once`() {
        val bearer = mint("ingest")
        authorizer.check(request(bearer, ingest())).let {
            it.allowed shouldBe true
            it.status shouldBe 200
        }
        refused(authorizer.check(request(bearer, ingest())), 429, "SRT-012")
    }

    @Test
    fun `a token of another scope is 403 SRT-004 and is not counted`() {
        listOf("reporting", "monitoring", "filing-workflow").forEach {
            refused(authorizer.check(request(mint(it), ingest())), 403, "SRT-004")
        }
        assertNotCounted()
    }

    @Test
    fun `a missing, empty or unknown required scope is 500 SRT-010 and is not counted`() {
        val contexts =
            listOf(emptyMap<String, String>()) +
                listOf("", "Ingest", "ingest ", "ingest,reporting", "*", "?", "other").map {
                    mapOf("required-scope" to it)
                }
        contexts.forEach { refused(authorizer.check(request(mint("ingest"), it)), 500, "SRT-010") }
        faults() shouldBe contexts.size.toDouble()
        assertNotCounted()
    }

    @Test
    fun `certification is allowed on an also-scope route only with environment SANDBOX`() {
        val route = ingest("also-scope" to "certification")
        listOf("PRODUCTION", "sandbox", "SANDBOX ", null).forEach {
            refused(authorizer.check(request(mint("certification", it), route)), 403, "SRT-004")
        }
        refused(authorizer.check(request(mint("certification", "SANDBOX"), ingest())), 403, "SRT-004")
        assertNotCounted()
        authorizer.check(request(mint("certification", "SANDBOX", "PRN-2"), route)).allowed shouldBe true
    }

    @Test
    fun `certification on a certification route is allowed, and ingest ignores environment on an also-scope route`() {
        val certification = mapOf("required-scope" to "certification")
        authorizer.check(request(mint("certification"), certification)).allowed shouldBe true
        val route = ingest("also-scope" to "certification")
        authorizer.check(request(mint("ingest", "PRODUCTION", "PRN-2"), route)).allowed shouldBe true
    }

    @Test
    fun `an unusable also-scope is 500 SRT-010`() {
        val bad =
            listOf(
                ingest("also-scope" to "reporting"),
                ingest("also-scope" to ""),
                ingest("also-scope" to "Certification"),
                mapOf("required-scope" to "reporting", "also-scope" to "certification"),
            )
        bad.forEach { refused(authorizer.check(request(mint("certification", "SANDBOX"), it)), 500, "SRT-010") }
        faults() shouldBe bad.size.toDouble()
        assertNotCounted()
    }

    @Test
    fun `a missing or unsigned token keeps 401 before the scope check`() {
        listOf(null, "not.a.jwt").forEach { refused(authorizer.check(request(it, emptyMap())), 401, "SRT-003") }
        faults() shouldBe 0.0
    }

    @Test
    fun `other route classes ignore the scope keys`() {
        val keys = ingest("also-scope" to "bogus")
        authorizer.check(request(null, keys, "console")).allowed shouldBe true
        faults() shouldBe 0.0
    }

    private companion object {
        const val TRACE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    }
}
