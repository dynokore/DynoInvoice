package com.dynokore.gst.gatewayauthz.check

import com.dynokore.gst.gatewayauthz.GatewayAuthzApplication
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import com.dynokore.gst.gatewayauthz.revocation.RevocationList
import com.dynokore.gst.gatewayauthz.token.TestTokens
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption
import io.envoyproxy.envoy.service.auth.v3.AttributeContext
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc
import io.envoyproxy.envoy.service.auth.v3.CheckRequest
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.MultiThreadIoEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollIoHandler
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The Check service end to end on the pod-local Unix socket (coordinator ruling INV-Q-4077): the application as
 * deployed, Envoy's envoy.service.auth.v3 messages over grpc-netty-shaded's epoll domain-socket transport. It also
 * measures the latency an allowed Check call adds (limits raised so the measured calls are all allowed), reported in
 * the merge request. Health and metrics answer on the management port without a token (INV-Q-4103 A).
 */
class ExtAuthzSocketTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `Check over the Unix socket allows a valid token, refuses a missing one, and its latency is measured`() {
        val socket = dir.resolve("ext-authz.sock")
        val tokens = TestTokens()
        val context = start(socket)
        val group = MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory())
        val channel = channel(socket, group)
        try {
            val management = context.environment.getProperty("local.management.port")
            probe(management, "/health/ready") shouldBe 503
            context.getBean(KeyMaterialCache::class.java).apply {
                keysRefreshed(listOf(tokens.verificationKey))
                revocationsRefreshed(RevocationList.EMPTY, fromSnapshot = true, readStartedAt = Instant.now())
            }
            probe(management, "/health/ready") shouldBe 200
            probe(management, "/health/live") shouldBe 200
            probe(management, "/metrics") shouldBe 200
            val stub = AuthorizationGrpc.newBlockingStub(channel)
            val token = tokens.mint(Instant.now(), lifetime = Duration.ofHours(1))

            val allowed = stub.check(checkRequest(token))
            allowed.status.code shouldBe 0
            allowed.okResponse.headersToRemoveList shouldBe listOf("x-workload-token", "x-deadline-ms")
            allowed.okResponse.responseHeadersToAddList.map(HeaderValueOption::getHeader).map { it.key } shouldBe
                listOf("RateLimit-Policy", "RateLimit")

            val denied = stub.check(checkRequest(null))
            denied.deniedResponse.status.codeValue shouldBe 401
            denied.deniedResponse.body shouldContain "SRT-003"

            reportLatency { stub.check(checkRequest(token)).status.code shouldBe 0 }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            group.shutdownGracefully()
            context.close()
        }
    }

    private fun start(socket: Path) = SpringApplicationBuilder(GatewayAuthzApplication::class.java).run(
        "--server.port=0",
        "--management.server.port=0",
        "--spring.grpc.server.netty.domain-socket-path=$socket",
        "--gateway-authz.token.issuer=${TestTokens.ISSUER}",
        "--gateway-authz.token.audience=${TestTokens.AUDIENCE}",
        "--gateway-authz.rate-limits.machine-per-principal=1000000",
        "--gateway-authz.rate-limits.machine-per-tenant=1000000",
    )

    private fun channel(
        socket: Path,
        group: EventLoopGroup,
    ) = NettyChannelBuilder.forAddress(DomainSocketAddress(socket.toString()))
        .channelType(EpollDomainSocketChannel::class.java)
        .eventLoopGroup(group)
        .usePlaintext()
        .build()

    /** A GET on the management port without any token (INV-Q-4103 A). */
    private fun probe(
        port: String?,
        path: String,
    ): Int {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).build()
        return HttpClient.newHttpClient().send(request, BodyHandlers.discarding()).statusCode()
    }

    private fun reportLatency(call: () -> Unit) {
        repeat(WARM_UP) { call() }
        val nanos =
            LongArray(MEASURED) {
                val start = System.nanoTime()
                call()
                System.nanoTime() - start
            }.sorted()
        fun micros(quantile: Double) = nanos[((nanos.size - 1) * quantile).toInt()] / NANOS_PER_MICRO
        println("CHECK-LATENCY n=$MEASURED p50=${micros(P50)}us p99=${micros(P99)}us max=${micros(1.0)}us")
    }

    private fun checkRequest(token: String?): CheckRequest {
        val http =
            AttributeContext.HttpRequest.newBuilder()
                .setPath("/v1/invoices")
                .setMethod("POST")
                .putHeaders("x-workload-token", "forged")
                .putHeaders("x-deadline-ms", "1")
        token?.let { http.putHeaders("authorization", "Bearer $it") }
        return CheckRequest.newBuilder()
            .setAttributes(
                AttributeContext.newBuilder()
                    .setRequest(AttributeContext.Request.newBuilder().setHttp(http))
                    .putContextExtensions("route-class", "machine-api"),
            ).build()
    }

    private companion object {
        const val WARM_UP = 2_000
        const val MEASURED = 5_000
        const val NANOS_PER_MICRO = 1_000
        const val P50 = 0.5
        const val P99 = 0.99
    }
}
