package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.binding.IdentityProvider
import com.dynokore.platform.serviceruntime.binding.OidcIssuer
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.composition.OperatorSessionAuditWriter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * Tenant-scoped operator session tokens (owner ruling INV-Q-4119, INV-REQ-862 b4, INV-REQ-1202 b4; coordinator
 * rulings INV-Q-4162, INV-Q-4171): accepted only from an operator-session key the authority replica holds and only
 * with `act` = {role, case}, `tenant_id` and at most 15 minutes of life; every other case is 401 (null).
 */
class OperatorSessionTokenTest {
    private val issuer = "https://id.test/realms/inv"
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val idp: KeyPair = rsa()
    private val operatorKey: KeyPair = rsa()
    private val replica = AuthorityReplica(Clock.systemUTC()).also { it.onSnapshotComplete(0) }
    private val decoder =
        NimbusJwtDecoder.withPublicKey(idp.public as RSAPublicKey).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(endUserTokenValidators(issuer, EndUserIdentityProperties("svc"))),
            )
        }
    private val resolver = JwtEndUserPrincipalResolver(decoder, replica, Clock.systemUTC())
    private val now = Instant.now()

    init {
        replica.operatorSessionKeys.accept(OperatorSessionKeysChanged(listOf(keyOf(operatorKey, KID))))
    }

    @Test
    fun `a valid operator session resolves to a tenant principal with its role and case`() {
        replica.accept(
            AuthorityChanged(
                tenant,
                "op-1",
                1,
                AuthorityRecord.GrantRecord("e1", listOf(Grant("read", ScopeLevel.ENTITY, "e1", now.minusSeconds(60)))),
            ),
        )
        resolver.resolve(session()) shouldBe
            EndUserPrincipal(
                "op-1",
                tenant,
                setOf(EntityRef("e1")),
                operatorSession = OperatorSession("platform.support", "CASE-5521"),
            )
        // exactly 15 minutes is the bound itself
        resolver
            .resolve(
                session(issuedAt = now.minusSeconds(60), expiresAt = now.minusSeconds(60).plus(Duration.ofMinutes(15))),
            )?.actor shouldBe
            "op-1"
    }

    @Test
    fun `expired, issued in the future, longer than 15 minutes, or without iat or exp is refused`() {
        resolver.resolve(session(issuedAt = now.minusSeconds(600), expiresAt = now.minusSeconds(120))) shouldBe null
        resolver.resolve(session(issuedAt = now.plusSeconds(300), expiresAt = now.plusSeconds(600))) shouldBe null
        resolver.resolve(session(expiresAt = now.plus(Duration.ofMinutes(15)).plusSeconds(1))) shouldBe null
        resolver.resolve(session(issuedAt = null)) shouldBe null
        resolver.resolve(session(expiresAt = null)) shouldBe null
        resolver.resolve(session(notBefore = now.plusSeconds(300))) shouldBe null
    }

    @Test
    fun `an act without a case or role, or not an object, is refused`() {
        resolver.resolve(session(act = mapOf("role" to "platform.support"))) shouldBe null
        resolver.resolve(session(act = mapOf("role" to "platform.support", "case" to " "))) shouldBe null
        resolver.resolve(session(act = mapOf("role" to "platform.support", "case" to 42))) shouldBe null
        resolver.resolve(session(act = mapOf("case" to "CASE-5521"))) shouldBe null
        resolver.resolve(session(act = "platform.support")) shouldBe null
    }

    @Test
    fun `the case is held to openOperatorSession's caseId - 1 to 64 chars, not blank, no colon or control character`() {
        fun act(
            case: String,
            role: String = "platform.support",
        ) = mapOf("role" to role, "case" to case)
        resolver.resolve(session(act = act("C".repeat(64))))?.operatorSession?.case shouldBe "C".repeat(64)
        resolver.resolve(session(act = act("C".repeat(65)))) shouldBe null
        resolver.resolve(session(act = act("\uD83D\uDE00".repeat(64))))?.actor shouldBe "op-1" // 64 code points
        resolver.resolve(session(act = act(""))) shouldBe null
        for (control in listOf("\n", "\r", "\t", "\u0000", "\u001B", "\u001F", "\u007F", "\u0085", "\u009F")) {
            resolver.resolve(session(act = act("CASE-1${control}reason"))) shouldBe null
            resolver.resolve(session(act = act("CASE-1", role = "platform.support$control"))) shouldBe null
        }
    }

    @Test
    fun `the INV-Q-4534 samples give the contract's results`() {
        fun caseOf(case: String) =
            resolver.resolve(session(act = mapOf("role" to "platform.support", "case" to case)))?.operatorSession?.case
        for (accepted in listOf("CASE-5521", " CASE-5521 ", "\uFEFFA\u3000", "\uD83D\uDE00".repeat(64))) {
            caseOf(accepted) shouldBe accepted
        }
        val whitespace =
            listOf(0x20, 0xA0, 0x1680) + (0x2000..0x200A) + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)
        val refused =
            listOf(
                "",
                "   ",
                "A:B",
                "A\nB",
                "A\u0085B",
                "\uFEFF",
                "\u3000",
                "\uD83D\uDE00".repeat(65),
                "C".repeat(65),
            ) +
                whitespace.map { String(Character.toChars(it)) } +
                whitespace.joinToString("") { String(Character.toChars(it)) }
        for (case in refused) caseOf(case) shouldBe null
    }

    @Test
    fun `a case holding a colon is refused, so it cannot pose as another case with its own reason (INV-Q-4529)`() {
        for (case in listOf(
            "CASE-1: approved",
            "CASE-1:approved",
            ":",
            ": ",
            "CASE-1:",
            ":CASE-1",
            "C".repeat(63) + ":",
        )) {
            resolver.resolve(session(act = mapOf("role" to "platform.support", "case" to case))) shouldBe null
        }
    }

    @Test
    fun `a valid case composes the INV-Q-4521 reasons - case CASE, then colon and the own reason`() {
        for (case in listOf("CASE-5521", "CASE 5521/a-b", "C".repeat(64))) {
            val session =
                resolver
                    .resolve(
                        session(act = mapOf("role" to "platform.support", "case" to case)),
                    )?.operatorSession
            session shouldBe OperatorSession("platform.support", case)
            OperatorSessionAuditWriter.reasonUnder(session!!, null) shouldBe "case $case"
            OperatorSessionAuditWriter.reasonUnder(session, "override approved") shouldBe
                "case $case: override approved"
        }
    }

    @Test
    fun `an operator session needs sub and a tenant other than the platform tenant`() {
        resolver.resolve(session(tenantClaim = null)) shouldBe null
        resolver.resolve(session(tenantClaim = "not-a-uuid")) shouldBe null
        resolver.resolve(session(tenantClaim = TenantId.PLATFORM.toString())) shouldBe null
        resolver.resolve(session(subject = null)) shouldBe null
    }

    @Test
    fun `an act token from the wrong key, an unknown kid or the identity provider is refused`() {
        resolver.resolve(session(signer = RSASSASigner(rsa().private))) shouldBe null // forged under the held kid
        resolver.resolve(session(kid = "other")) shouldBe null
        resolver.resolve(session(kid = null)) shouldBe null
        // an identity-provider token carrying act, otherwise valid for this service
        resolver.resolve(
            session(signer = RSASSASigner(idp.private), kid = null, audience = "svc", iss = issuer),
        ) shouldBe
            null
        resolver.resolve(
            session(signer = RSASSASigner(idp.private), kid = "idp", audience = "svc", iss = issuer),
        ) shouldBe
            null
    }

    @Test
    fun `any other token from an operator-session key is refused`() {
        // no act, but otherwise an identity-provider-shaped token signed with the operator-session key
        resolver.resolve(session(act = null, audience = "svc", iss = issuer)) shouldBe null
        resolver.resolve(session(act = null)) shouldBe null
        // the same claims signed by the identity provider are accepted, so the refusal is the key's
        resolver
            .resolve(
                session(act = null, audience = "svc", iss = issuer, signer = RSASSASigner(idp.private)),
            )?.operatorSession shouldBe
            null
        resolver
            .resolve(
                session(act = null, audience = "svc", iss = issuer, signer = RSASSASigner(idp.private)),
            )?.actor shouldBe
            "op-1"
    }

    @Test
    fun `the token's alg must be the key's own, never HMAC over the public key`() {
        resolver.resolve(session(alg = JWSAlgorithm.RS512)) shouldBe null
        val spki = operatorKey.public.encoded
        resolver.resolve(session(alg = JWSAlgorithm.HS256, signer = MACSigner(spki))) shouldBe null
        val hmacKey = OperatorSessionKeysChanged(listOf(keyOf(operatorKey, KID).copy(alg = "HS256")))
        replica.operatorSessionKeys.accept(hmacKey)
        resolver.resolve(session(alg = JWSAlgorithm.HS256, signer = MACSigner(spki))) shouldBe null
    }

    @Test
    fun `with no valid key held every act token is refused`() {
        val cold =
            JwtEndUserPrincipalResolver(decoder, AuthorityReplica(Clock.systemUTC()).also { it.onSnapshotComplete(0) })
        cold.resolve(session()) shouldBe null
        replica.operatorSessionKeys.accept(
            OperatorSessionKeysChanged(listOf(keyOf(operatorKey, KID).copy(validUntil = now.minusSeconds(1)))),
        )
        resolver.resolve(session()) shouldBe null
        replica.operatorSessionKeys.accept(
            OperatorSessionKeysChanged(listOf(keyOf(operatorKey, KID).copy(validFrom = now.plusSeconds(60)))),
        )
        resolver.resolve(session()) shouldBe null
        replica.operatorSessionKeys.accept(
            OperatorSessionKeysChanged(listOf(keyOf(operatorKey, KID).copy(publicKey = "bm90LWEta2V5"))),
        )
        resolver.resolve(session()) shouldBe null
        replica.operatorSessionKeys.accept(OperatorSessionKeysChanged(emptyList()))
        resolver.resolve(session()) shouldBe null
    }

    @Test
    fun `rotation - the next key verifies once published, a key left out of the next event no longer does`() {
        val next = rsa()
        resolver.resolve(session(signer = RSASSASigner(next.private), kid = "next")) shouldBe null
        replica.operatorSessionKeys.accept(
            OperatorSessionKeysChanged(listOf(keyOf(operatorKey, KID), keyOf(next, "next"))),
        )
        resolver.resolve(session(signer = RSASSASigner(next.private), kid = "next"))?.actor shouldBe "op-1"
        resolver.resolve(session())?.actor shouldBe "op-1"
        replica.operatorSessionKeys.accept(OperatorSessionKeysChanged(listOf(keyOf(next, "next"))))
        resolver.resolve(session()) shouldBe null
    }

    @Test
    fun `an EC operator-session key verifies its ES256 tokens`() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        replica.operatorSessionKeys.accept(OperatorSessionKeysChanged(listOf(keyOf(ec, "ec", "ES256"))))
        resolver
            .resolve(
                session(alg = JWSAlgorithm.ES256, signer = ECDSASigner(ec.private as ECPrivateKey), kid = "ec"),
            )?.operatorSession shouldBe
            OperatorSession("platform.support", "CASE-5521")
    }

    @Test
    fun `the wired resolver verifies against the context's authority replica`() {
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(IdentityProvider::class.java, { IdentityProvider { OidcIssuer(issuer, "$issuer/jwks") } })
            .withConfiguration(
                AutoConfigurations.of(
                    ServiceRuntimeConfiguration::class.java,
                    L1CompositionAutoConfiguration::class.java,
                ),
            ).withPropertyValues(
                "service-runtime.workload-identity.audience=svc",
                "service-runtime.end-user-identity.audience=svc",
            ).run { context ->
                val wired = context.getBean(EndUserPrincipalResolver::class.java)
                wired.resolve(session()) shouldBe null // no key held yet
                context
                    .getBean(
                        AuthorityReplica::class.java,
                    ).operatorSessionKeys
                    .accept(OperatorSessionKeysChanged(listOf(keyOf(operatorKey, KID))))
                wired.resolve(session())?.operatorSession shouldBe OperatorSession("platform.support", "CASE-5521")
            }
    }

    @Suppress("LongParameterList")
    private fun session(
        act: Any? = mapOf("role" to "platform.support", "case" to "CASE-5521"),
        subject: String? = "op-1",
        tenantClaim: String? = tenant.toString(),
        issuedAt: Instant? = now,
        expiresAt: Instant? = now.plus(Duration.ofMinutes(10)),
        notBefore: Instant? = null,
        kid: String? = KID,
        alg: JWSAlgorithm = JWSAlgorithm.RS256,
        signer: JWSSigner = RSASSASigner(operatorKey.private),
        audience: String? = null,
        iss: String? = null,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issueTime(issuedAt?.let(Date::from))
                .expirationTime(expiresAt?.let(Date::from))
                .notBeforeTime(notBefore?.let(Date::from))
                .audience(audience)
                .issuer(iss)
                .apply { act?.let { claim("act", it) } }
                .apply { tenantClaim?.let { claim(JwtEndUserPrincipalResolver.TENANT_CLAIM, it) } }
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(alg).keyID(kid).build(), claims)
        jwt.sign(signer)
        return "Bearer " + jwt.serialize()
    }

    private fun keyOf(
        pair: KeyPair,
        kid: String,
        alg: String = "RS256",
    ) = OperatorSessionKey(
        kid,
        alg,
        Base64.getEncoder().encodeToString(pair.public.encoded),
        now.minusSeconds(3600),
        now.plusSeconds(3600),
    )

    private companion object {
        const val KID = "ops-2026-10"

        fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
