package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date

/** A `link:` token verifies only against a LINK_SESSION key (owner ruling 2026-11-07; INV-AC-17808). */
class LinkSessionTokenTest {
    private val k1 = rsa()
    private val k2 = rsa()
    private val k3 = rsa()
    private val idp = rsa()
    private val now = Instant.now()
    private val tenantId = "00000000-0000-0000-0000-00000000000a"
    private val sub = "link:LNK-aaaaaaaaaaaaaaaaaaaaaa"
    private val replica = AuthorityReplica(Clock.systemUTC(), OperationRoleMap { emptyMap() })
    private val resolver =
        JwtEndUserPrincipalResolver(
            NimbusJwtDecoder.withPublicKey(idp.public as RSAPublicKey).build(),
            replica,
            Clock.systemUTC(),
        )

    init {
        publish()
    }

    private fun publish(withLink: Boolean = true) {
        fun item(
            pair: KeyPair,
            kid: String,
            use: String,
        ) = OperatorSessionKey(
            kid,
            "RS256",
            Base64.getEncoder().encodeToString(pair.public.encoded),
            now.minusSeconds(60),
            now.plusSeconds(3600),
            use,
        )
        replica.keySets.accept(
            OperatorSessionKeysChanged(
                listOfNotNull(
                    item(k1, "K1", "OPERATOR_SESSION"),
                    item(k2, "K2", "MACHINE_ACCESS"),
                    item(k3, "K3", "LINK_SESSION").takeIf { withLink },
                ),
            ),
        )
    }

    private fun token(
        signer: KeyPair = k3,
        kid: String = "K3",
        typ: String? = "link-session+jwt",
        subject: String = sub,
        tenant: String? = tenantId,
        issuedAt: Instant = now,
        lifetimeSeconds: Long = 600,
        act: Boolean = false,
        expiresAt: Instant = issuedAt.plusSeconds(lifetimeSeconds),
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .jwtID("j-1")
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .apply { tenant?.let { claim("tenant_id", it) } }
                .apply { if (act) claim("act", mapOf("role" to "r", "case" to "c")) }
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID(kid)
                .apply { typ?.let { type(JOSEObjectType(it)) } }
                .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(RSASSASigner(signer.private))
        return "Bearer " + jwt.serialize()
    }

    @Test
    fun `INV-AC-17808 a link token signed with the LINK_SESSION key resolves to a principal`() {
        val principal = resolver.resolve(token())
        principal?.actor shouldBe sub
        principal?.tenant.toString() shouldBe tenantId
        principal?.entities shouldBe emptySet()
    }

    @Test
    fun `INV-AC-17808 the same claims signed with the operator-session key resolve to null`() {
        resolver.resolve(token(k1, "K1")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 the same claims signed with the machine-access key resolve to null`() {
        resolver.resolve(token(k2, "K2")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a token signed with the LINK_SESSION key under an unknown kid resolves to null`() {
        resolver.resolve(token(kid = "nope")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a typ of at+jwt resolves to null`() {
        resolver.resolve(token(typ = "at+jwt")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a missing typ resolves to null`() {
        resolver.resolve(token(typ = null)) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a token carrying act resolves to null`() {
        resolver.resolve(token(act = true)) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a short linkRef resolves to null`() {
        resolver.resolve(token(subject = "link:LNK-short")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a capital Link prefix resolves to null`() {
        resolver.resolve(token(subject = "Link:LNK-aaaaaaaaaaaaaaaaaaaaaa")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a space after the prefix resolves to null`() {
        resolver.resolve(token(subject = "link: LNK-aaaaaaaaaaaaaaaaaaaaaa")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a linkRef with a trailing newline resolves to null`() {
        resolver.resolve(token(subject = "link:LNK-aaaaaaaaaaaaaaaaaaaaaa\n")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a token without tenant_id resolves to null`() {
        resolver.resolve(token(tenant = null)) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a token with an invalid tenant_id resolves to null`() {
        resolver.resolve(token(tenant = "not-a-tenant")) shouldBe null
    }

    @Test
    fun `INV-AC-17808 an expired token resolves to null`() {
        resolver.resolve(token(issuedAt = now.minusSeconds(1200), lifetimeSeconds = 600)) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a 16-minute lifetime resolves to null`() {
        resolver.resolve(token(lifetimeSeconds = 960)) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a 15-minute lifetime is accepted`() {
        (resolver.resolve(token(lifetimeSeconds = 900)) != null) shouldBe true
    }

    @Test
    fun `INV-AC-17808 exp not after iat resolves to null`() {
        resolver.resolve(token(lifetimeSeconds = 0)) shouldBe null
    }

    @Test
    fun `INV-AC-17808 a token issued in the future beyond the skew resolves to null`() {
        resolver.resolve(token(issuedAt = now.plusSeconds(300))) shouldBe null
    }

    @Test
    fun `INV-AC-17808 an empty link key set refuses every link token`() {
        publish(withLink = false)
        resolver.resolve(token()) shouldBe null
    }

    @Test
    fun `INV-AC-17808 an identity-provider token with a link subject resolves to null`() {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(sub)
                .issuer("https://id.test")
                .expirationTime(Date.from(now.plusSeconds(300)))
                .issueTime(Date.from(now))
                .claim("tenant_id", tenantId)
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims)
        jwt.sign(RSASSASigner(idp.private))
        resolver.resolve("Bearer " + jwt.serialize()) shouldBe null
    }

    private companion object {
        fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
