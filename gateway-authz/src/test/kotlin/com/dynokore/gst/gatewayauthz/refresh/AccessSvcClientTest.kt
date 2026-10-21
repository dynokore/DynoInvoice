package com.dynokore.gst.gatewayauthz.refresh

import com.dynokore.gst.gatewayauthz.GatewayAuthzConfiguration
import com.dynokore.gst.gatewayauthz.MutableClock
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.identity.ProjectedServiceAccountTokenSource
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenPropagationInterceptor
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The access-svc client against an HTTP stub that conforms to INV-API-4001 and refuses (403, SRT-004) every caller
 * other than the pod's workload token (x-principal workload, INV-Q-4006 A).
 */
class AccessSvcClientTest {
    @TempDir
    lateinit var dir: Path

    private val workload = "workload-token-of-the-gateway"
    private val seen = CopyOnWriteArrayList<Map<String, String>>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> answer(exchange) }
            start()
        }
    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    private val publicKey = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

    @AfterEach
    fun stop() = server.stop(0)

    private fun answer(exchange: HttpExchange) {
        seen += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() }
        val presented = exchange.requestHeaders.getFirst("X-Workload-Token")
        val others =
            exchange.requestHeaders.keys.map { it.lowercase() }.filter {
                it == "authorization" ||
                    it == "cookie"
            }
        val (status, body) =
            when {
                presented != workload || others.isNotEmpty() -> {
                    403 to """{"code":"SRT-004"}"""
                }

                exchange.requestURI.path == "/internal/v1/access/verification-keys" -> {
                    200 to """{"keys":[{"kid":"2026-09","alg":"RS256","publicKey":"$publicKey"}]}"""
                }

                exchange.requestURI.path == "/internal/v1/access/revocations" -> {
                    200 to
                        """{"listVersion":42,"snapshot":${exchange.requestURI.query?.contains("sinceVersion") != true},
                        "items":[{"entryId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","version":41,"kind":"PRINCIPAL",
                        "principalRef":"erp-ingest-01","revokedAt":"2026-10-12T08:00:00Z","expiresAt":"2026-10-12T08:15:00Z"},
                        {"entryId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","version":42,"kind":"FUTURE",
                        "revokedAt":"2026-10-12T08:00:00Z","expiresAt":"2026-10-12T08:15:00Z"}]}"""
                }

                else -> {
                    404 to "{}"
                }
            }
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun clientWith(tokenFile: Path?): AccessSvcClient {
        val builder = RestClient.builder().baseUrl(baseUrl)
        if (tokenFile != null) {
            builder.requestInterceptor(
                WorkloadTokenPropagationInterceptor(ProjectedServiceAccountTokenSource(tokenFile)),
            )
        }
        return AccessSvcClient(builder.build())
    }

    @Test
    fun `reads keys and the revocation list with the projected workload token only`() {
        val tokenFile = Files.writeString(dir.resolve("token"), "$workload\n")
        val client = clientWith(tokenFile)

        val keys = client.verificationKeys()
        keys.single().kid shouldBe "2026-09"
        keys.single().publicKey.toList() shouldBe listOf<Byte>(1, 2, 3)

        val snapshot = client.revocations(null, null)
        snapshot.snapshot shouldBe true
        snapshot.listVersion shouldBe 42
        snapshot.items.map { it.kind.name } shouldBe listOf("PRINCIPAL", "UNKNOWN")
        client.revocations(41, "c1").snapshot shouldBe false
        seen.last()["x-workload-token"] shouldBe workload
    }

    @Test
    fun `the stub refuses a caller without the workload token, with a wrong one or with other credentials`() {
        val noToken = clientWith(null)
        assertThrows<RestClientResponseException> { noToken.verificationKeys() }.statusCode.value() shouldBe 403
        assertThrows<RestClientResponseException> { noToken.revocations(null, null) }.statusCode.value() shouldBe 403

        val wrong = clientWith(Files.writeString(dir.resolve("wrong"), "someone-else"))
        assertThrows<RestClientResponseException> { wrong.verificationKeys() }.statusCode.value() shouldBe 403

        val bearer =
            RestClient
                .builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer $workload")
                .build()
        assertThrows<RestClientResponseException> { AccessSvcClient(bearer).verificationKeys() }
            .statusCode
            .value() shouldBe 403
    }

    @Test
    fun `a refused call leaves the cache as it was and the refresh then recovers (INV-Q-3973 A)`() {
        val tokenFile = Files.writeString(dir.resolve("token"), "rotating")
        val cache = KeyMaterialCache()
        val refresher = KeyMaterialRefresher(clientWith(tokenFile), cache, MutableClock())

        refresher.refreshOnce() shouldBe false
        cache.loaded shouldBe false

        Files.writeString(tokenFile, workload) // the kubelet rotates the projected file; read fresh on every call
        refresher.refreshOnce() shouldBe true
        cache.loaded shouldBe true
    }

    @Test
    fun `the application builds the client with the runtime interceptors and the schedule loads key material`() {
        val tokenFile = Files.writeString(dir.resolve("projected"), workload)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceRuntimeConfiguration::class.java))
            .withUserConfiguration(GatewayAuthzConfiguration::class.java)
            .withPropertyValues(
                "service-runtime.workload-identity.audience=gateway-authz",
                "service-runtime.workload-identity.token-path=$tokenFile",
                "gateway-authz.token.issuer=https://access.invoice.test",
                "gateway-authz.token.audience=https://api.invoice.test",
                "gateway-authz.access-svc.base-url=$baseUrl",
            ).withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(KeyMaterialSource::class.java).javaClass shouldBe AccessSvcClient::class.java
                val cache = context.getBean(KeyMaterialCache::class.java)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!cache.loaded && System.nanoTime() < deadline) Thread.onSpinWait()
                cache.loaded shouldBe true
                seen.map { it["x-workload-token"] }.distinct() shouldBe listOf(workload)
            }
    }

    @Test
    fun `without a base URL there is no client`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceRuntimeConfiguration::class.java))
            .withUserConfiguration(GatewayAuthzConfiguration::class.java)
            .withPropertyValues("service-runtime.workload-identity.audience=gateway-authz")
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                context.getBeansOfType(KeyMaterialSource::class.java).size shouldBe 0
            }
    }
}
