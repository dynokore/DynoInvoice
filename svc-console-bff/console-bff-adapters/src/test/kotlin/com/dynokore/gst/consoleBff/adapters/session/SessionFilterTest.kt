package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.application.session.PrincipalStanding
import com.dynokore.gst.consoleBff.application.session.SessionResolver
import com.dynokore.gst.consoleBff.application.session.SessionRow
import com.dynokore.gst.consoleBff.application.session.SessionStore
import com.dynokore.gst.consoleBff.application.session.Standing
import com.dynokore.gst.consoleBff.domain.session.SessionPolicy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** INV-AC-14457: the filter answers 401 SRT-003 for each refusal, passes a resolved session and fails closed. */
class SessionFilterTest {
    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val good = "A".repeat(43)

    private class Store(
        val rows: MutableMap<String, SessionRow>,
    ) : SessionStore {
        override fun find(hash: String) = rows[hash]

        override fun touch(
            hash: String,
            now: Instant,
        ) = Unit

        override fun delete(hash: String) {
            rows.remove(hash)
        }
    }

    private fun filter(
        standing: Standing = Standing.ACTIVE,
        opened: Instant = now.minusSeconds(60),
        publicPaths: Set<String> = emptySet(),
    ): ConsoleSessionFilter {
        val hash =
            com.dynokore.gst.consoleBff.domain.session.SessionIdHash.of(
                (
                    com.dynokore.gst.consoleBff.domain.session.SessionId
                        .parse(good) as com.dynokore.gst.consoleBff.domain.session.SessionIdParse.Parsed
                ).id,
            )
        val store = Store(mutableMapOf(hash to SessionRow(hash, "p-1", "11111111-1111-1111-1111-111111111111", 7, opened, opened)))
        val resolver =
            SessionResolver(
                SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12)),
                store,
                object : PrincipalStanding {
                    override fun of(
                        tenantId: String,
                        principalId: String,
                        authorityVersionAtOpen: Long,
                    ) = standing
                },
                clock,
            )
        return ConsoleSessionFilter(resolver, publicPaths)
    }

    private fun call(
        filter: ConsoleSessionFilter,
        path: String = "/api/x",
        vararg cookies: Cookie,
    ): Pair<MockHttpServletResponse, MockHttpServletRequest> {
        val request = MockHttpServletRequest("GET", path).apply { requestURI = path }
        if (cookies.isNotEmpty()) request.setCookies(*cookies)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to request
    }

    private fun refused(response: MockHttpServletResponse) {
        response.status shouldBe 401
        response.contentAsString shouldContain "SRT-003"
    }

    @Test
    fun `INV-AC-14457 a request with no cookie is 401 SRT-003`() {
        refused(call(filter()).first)
    }

    @Test
    fun `INV-AC-14457 two cookies of the name are 401 SRT-003`() {
        refused(call(filter(), "/api/x", Cookie(SessionCookie.NAME, good), Cookie(SessionCookie.NAME, good)).first)
    }

    @Test
    fun `INV-AC-14457 an unknown cookie is 401 SRT-003`() {
        refused(call(filter(), "/api/x", Cookie(SessionCookie.NAME, "B".repeat(43))).first)
    }

    @Test
    fun `INV-AC-14457 a malformed cookie and a cookie of another name are 401 SRT-003`() {
        refused(call(filter(), "/api/x", Cookie(SessionCookie.NAME, "short")).first)
        refused(call(filter(), "/api/x", Cookie("console-session", good)).first)
    }

    @Test
    fun `INV-AC-14457 an expired session is 401 SRT-003`() {
        refused(call(filter(opened = now.minus(Duration.ofHours(13))), "/api/x", Cookie(SessionCookie.NAME, good)).first)
    }

    @Test
    fun `INV-AC-14457 a revoked principal and an unknown standing are 401 SRT-003`() {
        refused(call(filter(Standing.REVOKED), "/api/x", Cookie(SessionCookie.NAME, good)).first)
        refused(call(filter(Standing.UNKNOWN), "/api/x", Cookie(SessionCookie.NAME, good)).first)
    }

    @Test
    fun `INV-AC-14457 a valid session carries its principal and tenant and the attributes do not leak`() {
        val f = filter()
        val (response, request) = call(f, "/api/x", Cookie(SessionCookie.NAME, good))
        response.status shouldBe 200
        request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) shouldBe "p-1"
        request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) shouldBe "11111111-1111-1111-1111-111111111111"
        // a request carrying attributes but no cookie gets neither them nor a pass
        val next = MockHttpServletRequest("GET", "/api/x").apply { requestURI = "/api/x" }
        next.setAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, "forged")
        val answer = MockHttpServletResponse()
        f.doFilter(next, answer, MockFilterChain())
        refused(answer)
        next.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) shouldBe null
    }

    @Test
    fun `INV-AC-14457 the public paths are empty by default`() {
        ConsoleSessionFilter.PUBLIC_PATHS shouldBe emptySet()
    }

    @Test
    fun `INV-AC-14457 a path equal to a public name passes and look-alikes do not`() {
        val f = filter(publicPaths = setOf("/x"))
        call(f, "/x").first.status shouldBe 200
        listOf("/x/", "/X", "/x%2F", "/x/y", "/").forEach { refused(call(f, it).first) }
    }

    @Test
    fun `INV-AC-14457 the cookie renders exactly the literal`() {
        val literal = "__Host-console-session=abc; Path=/; Secure; HttpOnly; SameSite=Strict"
        SessionCookie.render("abc") shouldBe literal
        (SessionCookie.render("abc") + "; Domain=example.com") shouldBe literal + "; Domain=example.com"
        ((SessionCookie.render("abc") + "; Domain=example.com") == literal) shouldBe false
    }
}
