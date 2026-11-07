package com.dynokore.gst.evidence.retention

import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * The identity provider of the retention tests (INV-T-12601): a test key whose key set a loopback server serves at
 * [jwkSetUri] for the whole test JVM. The production `EndUserPrincipalResolver` of `IdentityConfiguration` verifies the
 * tokens [token] signs; no resolver of the test stands in for it. The issuer and the audience are the installation
 * values the Gradle test task sets as system properties.
 */
object TestIdentity {
    private const val KEY_ID = "k1"
    private val key: RSAKey = RSAKeyGenerator(2048).keyID(KEY_ID).generate()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
            createContext("/jwks") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            executor = null
            start()
        }

    /** Where the test serves its key set; the value of `evidence.identity-provider.jwk-set-uri`. */
    val jwkSetUri: String = "http://127.0.0.1:${server.address.port}/jwks"

    private fun installation(name: String): String = checkNotNull(System.getProperty(name)) { "$name is not set" }

    /** A token of [subject] in [tenant], signed by the test key, for the issuer and audience of the installation. */
    fun token(
        subject: String,
        tenant: UUID,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(installation("evidence.identity-provider.issuer-uri"))
                .audience(installation("service-runtime.end-user-identity.audience"))
                .claim("tenant_id", tenant.toString())
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims)
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }
}

/**
 * Marks a test whose actor holds the TENANT-scope `tenant.admin` grant, seeded into the production `AuthorityReplica`
 * by [RetentionService]; `setPolicy` is decided by `AuthorityReplica.decide` for `vp-43.set` as in production.
 */
@Configuration(proxyBeanMethods = false)
class RetentionBeans

/** Only the workload tokens: no end-user resolver of the test, so the production one is built. */
@Configuration(proxyBeanMethods = false)
class WorkloadTokensOnly {
    /** `token-<name>` is verified as subject `sa-<name>`; every other token is refused. */
    @Bean
    @Primary
    fun retentionWorkloadVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            val value = token?.value
            if (value != null && value.startsWith("token-")) {
                WorkloadVerification.Verified("sa-" + value.removePrefix("token-"))
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }
}

/** A signed token of an actor with no grant: the permission decision refuses (403 SRT-004). */
@Configuration(proxyBeanMethods = false)
class EndUserTokensOnly
