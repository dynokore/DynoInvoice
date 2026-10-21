package com.dynokore.gst.gatewayauthz.token

import com.dynokore.gst.gatewayauthz.MutableClock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class AccessTokenVerifierTest {
    private val clock = MutableClock()
    private val tokens = TestTokens()
    private val verifier =
        AccessTokenVerifier(
            TokenSettings(TestTokens.ISSUER, TestTokens.AUDIENCE),
            { listOf(tokens.verificationKey) },
            clock,
        )

    @Test
    fun `a valid machine token yields client_id, sub, jti, iat, tenant and its machine scope`() {
        val token = verifier.verify(tokens.mint(clock.instant(), jti = "j-1")).shouldNotBeNull()
        token.clientId shouldBe "PRN-00088"
        token.subject shouldBe "PRN-00088"
        token.tokenId shouldBe "j-1"
        token.tenantId shouldBe "tenant-1"
        token.machineScope shouldBe "ingest"
    }

    @Test
    fun `a token whose scope is not exactly one machine scope is refused (INV-Q-4085 A)`() {
        verifier.verify(tokens.mint(clock.instant(), scope = "ingest reporting")).shouldBeNull()
        verifier.verify(tokens.mint(clock.instant(), scope = "openid")).shouldBeNull()
        verifier.verify(tokens.mint(clock.instant(), scope = null)).shouldBeNull()
    }

    @Test
    fun `a bad signature is refused`() {
        val forged = TestTokens(kid = tokens.kid).mint(clock.instant())
        verifier.verify(forged).shouldBeNull()
        verifier.verify(TestTokens(kid = "other").mint(clock.instant())).shouldBeNull()
    }

    @Test
    fun `an expired token is refused`() {
        val token = tokens.mint(clock.instant(), lifetime = Duration.ofMinutes(1))
        clock.advance(Duration.ofMinutes(1))
        verifier.verify(token).shouldBeNull()
    }

    @Test
    fun `a wrong iss, aud or typ and a token without iat or sub are refused`() {
        val now = clock.instant()
        verifier.verify(tokens.mint(now, issuer = "https://other.test")).shouldBeNull()
        verifier.verify(tokens.mint(now, audience = "https://other.test")).shouldBeNull()
        verifier.verify(tokens.mint(now, type = "JWT")).shouldBeNull()
        verifier.verify(tokens.mint(now, issuedAt = null)).shouldBeNull()
        verifier.verify(tokens.mint(now, clientId = null, subject = null)).shouldBeNull()
        verifier.verify(tokens.mint(now, type = "application/at+jwt")).shouldNotBeNull()
    }

    @Test
    fun `no key material, no token settings or a malformed token is refused`() {
        val cold = AccessTokenVerifier(TokenSettings(TestTokens.ISSUER, TestTokens.AUDIENCE), { null }, clock)
        cold.verify(tokens.mint(clock.instant())).shouldBeNull()
        verifier.verify("not-a-jwt").shouldBeNull()
        val unset = AccessTokenVerifier(TokenSettings(), { listOf(tokens.verificationKey) }, clock)
        unset.verify(tokens.mint(clock.instant())).shouldBeNull()
    }
}
