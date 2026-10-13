package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.binding.IdentityProvider
import com.dynokore.platform.serviceruntime.binding.OidcIssuer
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.JobCommandServlet
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.confirmVerified
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * INV-REQ-1202 (owner ruling INV-Q-3716 A), as service-runtime wires it: the forwarded end-user JWT is verified
 * locally against the issuer's keys, fetched once and cached, with no network call per request; actor = `sub`, tenant =
 * `tenant_id`. Plain identity headers are never trusted: forged ones with no valid token are 401.
 */
class LocalTokenVerificationTest {
    private val issuer = "https://id.test/realms/inv"
    private val tenant = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val fetches = AtomicInteger()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            val body = JWKSet(RSAKey.Builder(keys.public as RSAPublicKey).keyID("k1").build()).toString().toByteArray()
            createContext("/jwks") { exchange ->
                fetches.incrementAndGet()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    private val runner =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withConfiguration(AutoConfigurations.of(ServiceRuntimeConfiguration::class.java, L1CompositionAutoConfiguration::class.java))
            .withPropertyValues("service-runtime.workload-identity.audience=svc", "service-runtime.end-user-identity.audience=svc")
            .withBean(IdentityProvider::class.java, { IdentityProvider { OidcIssuer(issuer, "http://127.0.0.1:${server.address.port}/jwks") } })

    @AfterEach
    fun stop() = server.stop(0)

    private fun token(
        subject: String = "p1",
        signer: KeyPair = keys,
    ): String {
        val claims =
            JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience("svc")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .issueTime(Date.from(Instant.now()))
                .claim(JwtEndUserPrincipalResolver.TENANT_CLAIM, tenant.toString())
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims)
        jwt.sign(RSASSASigner(signer.private as RSAPrivateKey))
        return "Bearer " + jwt.serialize()
    }

    @Test
    fun `tokens are verified against the cached issuer keys, with no network call per request`() {
        runner.run { context ->
            val resolver = context.getBean(EndUserPrincipalResolver::class.java)
            fetches.get() shouldBe 0 // nothing fetched at start-up
            repeat(25) { n ->
                val principal = resolver.resolve(token(subject = "user-$n"))!!
                principal.actor shouldBe "user-$n"
                principal.tenant?.value shouldBe tenant
            }
            fetches.get() shouldBe 1
        }
    }

    @Test
    fun `forged identity headers with no valid token are 401, and nothing runs`() {
        runner.run { context ->
            val resolver = context.getBean(EndUserPrincipalResolver::class.java)
            val commands = mockk<JobCommands>()
            val idempotency = mockk<IdempotencyStore>()
            val servlet = JobCommandServlet(commands, resolver, mockk<TenantScope>(), idempotency, null, Clock.systemUTC())
            val forger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            for (authorization in listOf(null, "Bearer not-a-jwt", token(signer = forger))) {
                val request = MockHttpServletRequest("POST", "/internal/v1/jobs/nightly/run")
                listOf("X-Tenant-Id", "X-Tenant", "tenant_id", "X-Actor", "X-User-Id", "X-Forwarded-User", "X-Auth-Request-User")
                    .forEach { request.addHeader(it, if (it.contains("ten", ignoreCase = true)) tenant.toString() else "p1") }
                request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
                authorization?.let { request.addHeader("Authorization", it) }
                request.setContent("{}".toByteArray())
                request.contentType = "application/json"
                val response = MockHttpServletResponse()
                servlet.service(request, response)
                response.status shouldBe 401
                response.contentAsString.contains("SRT-") shouldBe true
            }
            confirmVerified(commands, idempotency)
        }
    }
}
