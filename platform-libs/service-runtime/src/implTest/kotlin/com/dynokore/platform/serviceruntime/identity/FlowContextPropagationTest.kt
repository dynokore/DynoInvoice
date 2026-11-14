package com.dynokore.platform.serviceruntime.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import java.net.URI
import java.time.Duration

class FlowContextPropagationTest {
    private val operations =
        FlowContextOperations.of(
            listOf(
                "t" to
                    "operationId\tmethod\tpath\ncloseTenant\tPOST\t/v1/closeTenant\n" +
                    "putX\tPUT\t/v1/x/{id}\nsuffixY\tPOST\t/v1/y/{id}extra\n",
            ),
        )
    private val interceptor = FlowContextPropagationInterceptor(operations)
    private val execution = ClientHttpRequestExecution { _, _ -> MockClientHttpResponse(ByteArray(0), 200) }

    private fun send(
        method: HttpMethod,
        uri: String,
        preset: String? = null,
    ): String? {
        val request = MockClientHttpRequest(method, URI.create(uri))
        if (preset != null) request.headers.set(FlowContext.HEADER_NAME, preset)
        interceptor.intercept(request, ByteArray(0), execution)
        return request.headers.getFirst(FlowContext.HEADER_NAME)
    }

    private fun entry(): FlowContext =
        FlowContext.begin(
            FlowContextTestSupport.issuer(),
            FlowContextTestSupport.CLOCK,
            "tenant-1",
            FlowEntity.entity("E-1"),
            "MACHINE",
            "control-plane-svc",
            flowId = "flow-9",
        )

    @Test
    fun `INV-AC-8251 a flow entry opens a context the keys verify with its claims`() {
        val context = entry()
        val payload = checkNotNull(FlowContextTestSupport.keys().verify(context.compact))
        val read = checkNotNull(FlowContextParser.parse(payload, context.compact))
        read.tenant shouldBe "tenant-1"
        read.entity shouldBe FlowEntity.entity("E-1")
        read.principalKind shouldBe "MACHINE"
        read.callerService shouldBe "control-plane-svc"
        read.flowId shouldBe "flow-9"
        read.issuedAt shouldBe FlowContextTestSupport.NOW.epochSecond
        read.expiresAt shouldBe FlowContextTestSupport.NOW.epochSecond + 900
        context.toString().contains(context.compact) shouldBe false
    }

    @Test
    fun `INV-AC-8251 begin refuses a lifetime over 900 seconds and an empty claim`() {
        fun begin(
            lifetime: Duration = Duration.ofSeconds(900),
            tenant: String = "t",
        ) = FlowContext.begin(
            FlowContextTestSupport.issuer(),
            FlowContextTestSupport.CLOCK,
            tenant,
            FlowEntity.tenantWide(),
            "MACHINE",
            "control-plane-svc",
            lifetime = lifetime,
        )
        begin()
        shouldThrow<IllegalArgumentException> { begin(Duration.ofSeconds(901)) }
        shouldThrow<IllegalArgumentException> { begin(Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { begin(tenant = "") }
        shouldThrow<IllegalArgumentException> { begin(tenant = "x".repeat(65)) }
        val tricky = begin(tenant = "a\"b\\c")
        val payload = checkNotNull(FlowContextTestSupport.keys().verify(tricky.compact))
        checkNotNull(FlowContextParser.parse(payload, tricky.compact)).tenant shouldBe "a\"b\\c"
    }

    @Test
    fun `INV-AC-8251 while a context is held the call to a listed operation carries it unchanged`() {
        val context = entry()
        FlowContext.runWith(context) {
            send(HttpMethod.POST, "http://callee/v1/closeTenant") shouldBe context.compact
            send(HttpMethod.PUT, "http://callee/v1/x/7?q=1") shouldBe context.compact
            send(HttpMethod.POST, "http://callee/v1/y/7extra") shouldBe context.compact
        }
        FlowContext.current() shouldBe null
    }

    @Test
    fun `INV-AC-8251 a call to an operation not listed carries none and a hand-set header is removed`() {
        val context = entry()
        FlowContext.runWith(context) {
            send(HttpMethod.GET, "http://callee/v1/closeTenant") shouldBe null
            send(HttpMethod.POST, "http://callee/v1/closeTenantX") shouldBe null
            send(HttpMethod.PUT, "http://callee/v1/x/7/extra") shouldBe null
            send(HttpMethod.POST, "http://callee/v1/y/7") shouldBe null
            send(HttpMethod.POST, "http://callee/v1/closeTenant/more") shouldBe null
            send(HttpMethod.GET, "http://callee/v1/other", preset = "forged") shouldBe null
            send(HttpMethod.POST, "http://callee/v1/closeTenant", preset = "forged") shouldBe context.compact
        }
    }

    @Test
    fun `INV-AC-8251 a call outside a held context carries none, even with a hand-set header`() {
        send(HttpMethod.POST, "http://callee/v1/closeTenant") shouldBe null
        send(HttpMethod.POST, "http://callee/v1/closeTenant", preset = "forged") shouldBe null
    }

    @Test
    fun `INV-AC-8251 the test helper signs a header the fake keys verify and the outside key does not`() {
        val header = FlowContextTestSupport.contextFor("t", FlowEntity.tenantWide(), "MACHINE", "x-svc")
        (FlowContextTestSupport.keys().verify(header) != null) shouldBe true
        val outside =
            FlowContextTestSupport.contextFor(
                "t",
                FlowEntity.tenantWide(),
                "MACHINE",
                "x-svc",
                issuer = FlowContextTestSupport.outsideIssuer(),
            )
        FlowContextTestSupport.keys().verify(outside) shouldBe null
        FlowContextTestSupport.keys().verify("a.b") shouldBe null
        FlowContextTestSupport.keys().verify("a.b.c") shouldBe null
        FlowContextTestSupport.keys().verify("") shouldBe null
    }
}
