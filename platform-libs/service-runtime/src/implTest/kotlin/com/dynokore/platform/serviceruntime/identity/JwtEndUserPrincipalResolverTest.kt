package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.binding.IdentityProvider
import com.dynokore.platform.serviceruntime.binding.OidcIssuer
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ConsentScopeItem
import com.dynokore.platform.serviceruntime.replica.ConsentState
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID

/** INV-Q-3716, INV-REQ-1202 b4: actor from sub, tenant from tenant_id, entity set from the authority replica. */
class JwtEndUserPrincipalResolverTest {
    private val issuer = "https://id.test/realms/inv"
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val replica = AuthorityReplica(Clock.systemUTC()).also { it.onSnapshotComplete(0) }
    private val decoder =
        NimbusJwtDecoder.withPublicKey(keys.public as RSAPublicKey).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(endUserTokenValidators(issuer, EndUserIdentityProperties("svc"))),
            )
        }
    private val resolver = JwtEndUserPrincipalResolver(decoder, replica)
    private val grant = Grant("approve", ScopeLevel.ENTITY, "e1", Instant.now().minusSeconds(60))

    private fun token(
        tenantClaim: String? = tenant.toString(),
        subject: String = "p1",
        audience: String = "svc",
        iss: String = issuer,
        expiresAt: Instant = Instant.now().plusSeconds(300),
        signer: KeyPair = keys,
        kid: String? = null,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(iss)
                .audience(audience)
                .expirationTime(Date.from(expiresAt))
                .issueTime(Date.from(Instant.now()))
                .apply { tenantClaim?.let { claim(JwtEndUserPrincipalResolver.TENANT_CLAIM, it) } }
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims)
        jwt.sign(RSASSASigner(signer.private as RSAPrivateKey))
        return "Bearer " + jwt.serialize()
    }

    private fun publish(
        v: Long,
        record: AuthorityRecord?,
    ) = replica.accept(AuthorityChanged(tenant, "p1", v, record))

    private fun grantOn(
        v: Long,
        entity: String,
        grants: List<Grant> = listOf(grant),
    ) = publish(v, AuthorityRecord.GrantRecord(entity, grants))

    @Test
    fun `actor from sub, tenant from tenant_id, entity set from the replica's grants`() {
        grantOn(1, "e1")
        grantOn(2, "e2")
        resolver.resolve(token()) shouldBe EndUserPrincipal("p1", tenant, setOf(EntityRef("e1"), EntityRef("e2")))
        resolver.resolve(token(subject = "p2")) shouldBe EndUserPrincipal("p2", tenant, emptySet())
    }

    @Test
    fun `a token without tenant_id resolves to a platform operator with no entities`() {
        grantOn(1, "e1")
        resolver.resolve(token(tenantClaim = null)) shouldBe EndUserPrincipal("p1", null, emptySet())
    }

    @Test
    fun `an entity whose grant was revoked leaves the set at the next request`() {
        grantOn(1, "e1")
        grantOn(2, "e2")
        resolver.resolve(token())?.entities shouldBe setOf(EntityRef("e1"), EntityRef("e2"))
        grantOn(3, "e2", emptyList())
        resolver.resolve(token())?.entities shouldBe setOf(EntityRef("e1"))
        publish(4, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED))
        resolver.resolve(token())?.entities shouldBe emptySet()
        grantOn(1, "e1") // a redelivered older grant stays out
        resolver.resolve(token())?.entities shouldBe emptySet()
    }

    @Test
    fun `expired, unknown-scope and consent grants without a live consent are not held`() {
        grantOn(1, "e1", listOf(grant.copy(validUntil = Instant.now().minusSeconds(1))))
        grantOn(2, "e2", listOf(grant.copy(scopeLevel = ScopeLevel.UNKNOWN)))
        grantOn(3, "e3", listOf(grant.copy(scopeLevel = ScopeLevel.CONSENT)))
        resolver.resolve(token())?.entities shouldBe emptySet()
        val live = setOf(ConsentScopeItem.SUBMIT_INVOICE_DATA)
        publish(4, AuthorityRecord.ConsentRecord("e3", ConsentState.LIVE, live, Instant.now().minusSeconds(60)))
        resolver.resolve(token())?.entities shouldBe setOf(EntityRef("e3"))
    }

    @Test
    fun `with no authority version received the entity set is not current, never an empty set to refuse on`() {
        val cold = JwtEndUserPrincipalResolver(decoder, AuthorityReplica(Clock.systemUTC()))
        cold.resolve(token()) shouldBe EndUserPrincipal("p1", tenant, emptySet(), entitySetCurrent = false)
        grantOn(1, "e1")
        resolver.resolve(token())?.entitySetCurrent shouldBe true
    }

    @Test
    fun `a missing, invalid, expired or foreign token resolves to null`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        resolver.resolve(null) shouldBe null
        resolver.resolve("Basic abc") shouldBe null
        resolver.resolve("Bearer ") shouldBe null
        resolver.resolve("Bearer not-a-jwt") shouldBe null
        resolver.resolve(token(signer = other)) shouldBe null
        resolver.resolve(token(expiresAt = Instant.now().minusSeconds(120))) shouldBe null
        resolver.resolve(token(audience = "another-svc")) shouldBe null
        resolver.resolve(token(iss = "https://elsewhere.test")) shouldBe null
        resolver.resolve(token(tenantClaim = "not-a-uuid")) shouldBe null
        resolver.resolve(token(tenantClaim = TenantId.PLATFORM.toString())) shouldBe null
    }

    @Test
    fun `wired by ServiceRuntimeConfiguration when an IdentityProvider is bound, a service's own resolver wins`() {
        val runner =
            ApplicationContextRunner()
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .withConfiguration(
                    AutoConfigurations.of(
                        ServiceRuntimeConfiguration::class.java,
                        L1CompositionAutoConfiguration::class.java,
                    ),
                ).withPropertyValues(
                    "service-runtime.workload-identity.audience=svc",
                    "service-runtime.end-user-identity.audience=svc",
                )
        runner.run { it.getBeansOfType(EndUserPrincipalResolver::class.java).size shouldBe 0 }
        val bound =
            runner.withBean(
                IdentityProvider::class.java,
                { IdentityProvider { OidcIssuer(issuer, "$issuer/jwks") } },
            )
        bound.run { it.getBean(EndUserPrincipalResolver::class.java).shouldBeInstanceOf<JwtEndUserPrincipalResolver>() }
        val own = EndUserPrincipalResolver { null }
        bound.withBean(EndUserPrincipalResolver::class.java, { own }).run {
            it.getBean(EndUserPrincipalResolver::class.java) shouldBe own
        }
    }

    @Test
    fun `an unreachable issuer with no cached key gives SRT-011, with a cached key the token still verifies`() {
        var down = true
        val jwk = RSAKey.Builder(keys.public as RSAPublicKey).keyID("k1").build()
        val remote =
            JWKSource<SecurityContext> { selector, _ ->
                if (down) throw KeySourceException("issuer unreachable")
                selector.select(JWKSet(jwk))
            }
        val validators = endUserTokenValidators(issuer, EndUserIdentityProperties("svc"))
        val decoder =
            endUserJwtDecoder(LastKnownKeySource(remote)).apply {
                setJwtValidator(DelegatingOAuth2TokenValidator(validators))
            }
        val outage = JwtEndUserPrincipalResolver(decoder, replica)
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        shouldThrow<TokenIssuerUnavailableException> { outage.resolve(token(kid = "k1")) } // no key ever obtained
        down = false
        outage.resolve(token(kid = "k1"))?.actor shouldBe "p1"
        down = true
        outage.resolve(token(kid = "k1"))?.actor shouldBe "p1" // the cached key still verifies
        outage.resolve(token(kid = "k1", expiresAt = Instant.now().minusSeconds(120))) shouldBe null // still 401
        shouldThrow<TokenIssuerUnavailableException> { outage.resolve(token(kid = "k2", signer = other)) }
        down = false
        outage.resolve(token(kid = "k2", signer = other)) shouldBe null // issuer reachable, unknown key: 401
    }
}
