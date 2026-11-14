package com.dynokore.platform.serviceruntime.identity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.util.Base64
import java.util.UUID

private const val T1 = "aaaaaaaa-0000-0000-0000-000000000001"
private const val T10 = "aaaaaaaa-0000-0000-0000-000000000010"
private const val T10_PREFIX = "aaaaaaaa-0000-0000-0000-00000000001"
private const val CALLER = "control-plane-svc"
private const val ROWS =
    "operationId\tmethod\tpath\n" +
        "closeTenant\tPOST\t/v1/closeTenant\n" +
        "putX\tPUT\t/v1/x/{id}\n" +
        "suffixY\tPOST\t/v1/y/{id}extra\n"

class FlowContextFilterTest {
    private val tenants = TenantContextImpl()
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(FlowContextFilter::class.java) as Logger
    private var handled = 0
    private var boundTenant: TenantId? = null
    private var boundActor: String? = null
    private var held: FlowContext? = null

    @BeforeEach
    fun capture() {
        appender.start()
        logger.level = Level.INFO
        logger.addAppender(appender)
    }

    @AfterEach
    fun release() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun <T : Any> provider(
        type: Class<T>,
        bean: T?,
    ): ObjectProvider<T> =
        DefaultListableBeanFactory()
            .also { if (bean != null) it.registerSingleton(type.simpleName, bean) }
            .getBeanProvider(type)

    private fun filter(
        keys: FlowContextKeys? = FlowContextTestSupport.keys(),
        resolver: EndUserPrincipalResolver? = null,
        rows: String = ROWS,
        clock: Clock = FlowContextTestSupport.CLOCK,
    ) = FlowContextFilter(
        FlowContextOperations.of(listOf("test" to rows)),
        provider(FlowContextKeys::class.java, keys),
        provider(Clock::class.java, clock),
        provider(TenantContext::class.java, tenants),
        provider(EndUserPrincipalResolver::class.java, resolver),
    )

    private val chain =
        FilterChain { _, _ ->
            handled++
            boundTenant = runCatching { tenants.current() }.getOrNull()
            boundActor = runCatching { tenants.currentActor() }.getOrNull()
            held = FlowContext.current()
        }

    private fun call(
        filter: FlowContextFilter,
        method: String = "POST",
        path: String = "/v1/closeTenant",
        context: String? = null,
        caller: String? = CALLER,
        authorization: String? = null,
        chain: FilterChain = this.chain,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest(method, path)
        if (context != null) request.addHeader(FlowContext.HEADER_NAME, context)
        if (authorization != null) request.addHeader(HttpHeaders.AUTHORIZATION, authorization)
        if (caller != null) request.setAttribute(ServiceCaller.REQUEST_ATTRIBUTE, caller)
        return MockHttpServletResponse().also { filter.doFilter(request, it, chain) }
    }

    private fun signed(
        tenant: String = T1,
        entity: FlowEntity = FlowEntity.entity("E-1"),
        caller: String = CALLER,
        issuedAt: Long = FlowContextTestSupport.NOW.epochSecond,
        expiresAt: Long = issuedAt + 300,
        issuer: FlowContextIssuer = FlowContextTestSupport.issuer(),
    ) = FlowContextTestSupport.contextFor(tenant, entity, "HUMAN", caller, "flow-7", issuedAt, expiresAt, issuer)

    private fun person(
        tenant: String?,
        actor: String = "user-1",
    ) = EndUserPrincipalResolver { header ->
        if (header == "Bearer person") {
            EndUserPrincipal(actor, tenant?.let { TenantId(UUID.fromString(it)) }, emptySet<EntityRef>())
        } else {
            null
        }
    }

    private fun refused(response: MockHttpServletResponse) {
        response.status shouldBe 403
        response.contentAsString.contains("SRT-004") shouldBe true
        handled shouldBe 0
        shouldThrow<IllegalStateException> { tenants.current() }
    }

    @Test
    fun `INV-AC-8251 an unsigned tenant header is never a tenant source for a workload token`() {
        fun withHeaders(context: String?): MockHttpServletResponse {
            val request = MockHttpServletRequest("POST", "/v1/closeTenant")
            if (context != null) request.addHeader(FlowContext.HEADER_NAME, context)
            listOf("X-Tenant-Id", "X-Tenant", "Tenant-Id", "tenant_id", "X-Tenant-ID").forEach {
                request.addHeader(it, T10)
            }
            request.setAttribute(ServiceCaller.REQUEST_ATTRIBUTE, CALLER)
            return MockHttpServletResponse().also { filter().doFilter(request, it, chain) }
        }
        refused(withHeaders(null))
        val accepted = withHeaders(signed(T1))
        accepted.status shouldBe 200
        boundTenant shouldBe TenantId(UUID.fromString(T1))
    }

    private fun fields(): Map<String, String> {
        val line = appender.list.single { it.formattedMessage.startsWith("flow_context ") }.formattedMessage
        return Regex("""(\w+)="((?:[^"\\]|\\.)*)"""").findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `INV-AC-8251 a person token with tenant T-1 and a signed context for T-1 is accepted and binds T-1`() {
        val response = call(filter(resolver = person(T1)), context = signed(T1), authorization = "Bearer person")
        response.status shouldBe 200
        handled shouldBe 1
        boundTenant shouldBe TenantId(UUID.fromString(T1))
        boundActor shouldBe "user-1"
        held?.flowId shouldBe "flow-7"
        FlowContext.current() shouldBe null
        shouldThrow<IllegalStateException> { tenants.current() }
    }

    @Test
    fun `INV-AC-8251 a person token with tenant T-1 against a context for T-10 is refused`() {
        refused(call(filter(resolver = person(T1)), context = signed(T10), authorization = "Bearer person"))
        fields()["result"] shouldBe "REFUSED_TENANT"
    }

    @Test
    fun `INV-AC-8251 a person token with tenant T-10 against a context for T-1 is refused`() {
        refused(call(filter(resolver = person(T10)), context = signed(T1), authorization = "Bearer person"))
    }

    @Test
    fun `INV-AC-8251 a context tenant that is a prefix of the token tenant is refused`() {
        refused(call(filter(resolver = person(T10)), context = signed(T10_PREFIX), authorization = "Bearer person"))
    }

    @Test
    fun `INV-AC-8251 a context tenant in other letter case than the token tenant is refused`() {
        refused(call(filter(resolver = person(T1)), context = signed(T1.uppercase()), authorization = "Bearer person"))
    }

    @Test
    fun `INV-AC-8251 a workload token without a tenant claim and a signed context for T-1 is accepted and binds T-1`() {
        val response = call(filter(), context = signed(T1))
        response.status shouldBe 200
        handled shouldBe 1
        boundTenant shouldBe TenantId(UUID.fromString(T1))
        boundActor shouldBe "svc:control-plane-svc"
    }

    @Test
    fun `INV-AC-8251 a workload token with no X-Flow-Context is refused`() {
        refused(call(filter()))
        fields()["result"] shouldBe "REFUSED_NO_CONTEXT"
    }

    @Test
    fun `INV-AC-8251 a context signed by a key outside the key set is refused`() {
        refused(call(filter(), context = signed(issuer = FlowContextTestSupport.outsideIssuer())))
        fields()["result"] shouldBe "REFUSED_UNVERIFIED"
    }

    @Test
    fun `INV-AC-8251 a context with a tampered payload is refused`() {
        val parts = signed().split('.')
        val other = signed(tenant = T10).split('.')
        refused(call(filter(), context = "${parts[0]}.${other[1]}.${parts[2]}"))
    }

    @Test
    fun `INV-AC-8251 an unsigned plain JSON value and an alg none value in the header are refused`() {
        val payload =
            """{"tenant":"$T1","entity":{"kind":"TENANT_WIDE"},"principalKind":"HUMAN","callerService":"$CALLER",""" +
                """"flowId":"f","iat":1788998400,"exp":1788998700}"""
        refused(call(filter(), context = payload))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val none = header + "." + encoder.encodeToString(payload.toByteArray()) + "."
        refused(call(filter(), context = none))
    }

    @Test
    fun `INV-AC-8251 an expired context is refused at exp plus one second and at exp, and a context not yet issued`() {
        val now = FlowContextTestSupport.NOW.epochSecond
        refused(call(filter(), context = signed(issuedAt = now - 300, expiresAt = now - 1)))
        fields()["result"] shouldBe "REFUSED_EXPIRED"
        refused(call(filter(), context = signed(issuedAt = now - 300, expiresAt = now)))
        refused(call(filter(), context = signed(issuedAt = now + 1, expiresAt = now + 300)))
        call(filter(), context = signed(issuedAt = now - 299, expiresAt = now + 1)).status shouldBe 200
    }

    @Test
    fun `INV-AC-8251 a context that lives longer than 900 seconds is refused`() {
        val now = FlowContextTestSupport.NOW.epochSecond
        refused(call(filter(), context = signed(issuedAt = now, expiresAt = now + 901)))
        call(filter(), context = signed(issuedAt = now, expiresAt = now + 900)).status shouldBe 200
    }

    @Test
    fun `INV-AC-8251 a context signed for service X sent by service Y is refused, and so is no verified caller`() {
        refused(call(filter(), context = signed(caller = "canonical-svc")))
        fields()["result"] shouldBe "REFUSED_CALLER"
        refused(call(filter(), context = signed(), caller = null))
    }

    @Test
    fun `INV-AC-8251 a person token without a tenant claim is refused and a platform tenant context is refused`() {
        refused(call(filter(resolver = person(null)), context = signed(T1), authorization = "Bearer person"))
        refused(call(filter(), context = signed("ffffffff-ffff-ffff-ffff-ffffffffffff")))
        refused(call(filter(), context = signed("not-a-uuid")))
        refused(call(filter(), context = signed(T1.uppercase())))
    }

    @Test
    fun `INV-AC-8251 a person token that does not resolve is 401 and an unreachable issuer is 503, nothing stored`() {
        val unauthenticated = call(filter(resolver = person(T1)), context = signed(), authorization = "Bearer other")
        unauthenticated.status shouldBe 401
        fields()["result"] shouldBe "REFUSED_PRINCIPAL"
        fields()["flowId"] shouldBe "flow-7"
        fields()["tenant"] shouldBe T1
        appender.list.clear()
        val down = EndUserPrincipalResolver { throw TokenIssuerUnavailableException(RuntimeException("down")) }
        call(filter(resolver = down), context = signed(), authorization = "Bearer person").status shouldBe 503
        fields()["result"] shouldBe "REFUSED_ISSUER_UNAVAILABLE"
        fields()["callerService"] shouldBe CALLER
        handled shouldBe 0
    }

    @Test
    fun `INV-AC-8251 without keys every listed call is refused`() {
        refused(call(filter(keys = null), context = signed()))
    }

    @Test
    fun `INV-AC-8251 two X-Flow-Context headers are refused`() {
        val request = MockHttpServletRequest("POST", "/v1/closeTenant")
        request.addHeader(FlowContext.HEADER_NAME, signed())
        request.addHeader(FlowContext.HEADER_NAME, signed())
        request.setAttribute(ServiceCaller.REQUEST_ATTRIBUTE, CALLER)
        val response = MockHttpServletResponse().also { filter().doFilter(request, it, chain) }
        refused(response)
    }

    @Test
    fun `INV-AC-8455 an operation not listed is untouched with or without the header`() {
        call(filter(), method = "GET", path = "/v1/closeTenant").status shouldBe 200
        call(filter(), path = "/v1/closeTenantX").status shouldBe 200
        call(filter(), path = "/v1/x/7", method = "GET").status shouldBe 200
        call(filter(), path = "/v1/x/7/extra", method = "PUT").status shouldBe 200
        call(filter(), path = "/v1/x", method = "PUT").status shouldBe 200
        call(filter(), path = "/v1/y/7").status shouldBe 200
        call(filter(), path = "/v1/other").status shouldBe 200
        handled shouldBe 7
        appender.list.none { it.formattedMessage.startsWith("flow_context ") } shouldBe true
    }

    @Test
    fun `INV-AC-8455 a listed operation, a templated path and a context path are checked`() {
        refused(call(filter(), path = "/v1/closeTenant"))
        refused(call(filter(), method = "PUT", path = "/v1/x/7"))
        refused(call(filter(), path = "/v1/y/7extra"))
        refused(call(filter(), method = "PUT", path = "/v1/x/7extra"))
        call(filter(), method = "PUT", path = "/v1/x/7", context = signed()).status shouldBe 200
        val request = MockHttpServletRequest("POST", "/svc/v1/closeTenant")
        request.contextPath = "/svc"
        request.setAttribute(ServiceCaller.REQUEST_ATTRIBUTE, CALLER)
        MockHttpServletResponse().also { filter().doFilter(request, it, chain) }.status shouldBe 403
    }

    @Test
    fun `INV-AC-8251 requireEntity returns for the entity and for a tenant-wide flow and refuses any other string`() {
        val entity = FlowEntity.entity("E-1")
        val context = FlowContext("t", entity, "HUMAN", CALLER, "f", 1, 2, "x")
        context.requireEntity("E-1")
        shouldThrow<FlowContextRefusedException> { context.requireEntity("e-1") }
        shouldThrow<FlowContextRefusedException> { context.requireEntity("E-1 ") }
        shouldThrow<FlowContextRefusedException> { context.requireEntity("E-10") }
        shouldThrow<FlowContextRefusedException> { context.requireEntity("E-") }
        FlowContext("t", FlowEntity.tenantWide(), "MACHINE", CALLER, "f", 1, 2, "x").requireEntity("anything")
    }

    @Test
    fun `INV-AC-8251 an entity outside the context is answered 403 SRT-004, also when the exception is wrapped`() {
        val refusing =
            FilterChain { _, _ ->
                handled++
                checkNotNull(FlowContext.current()).requireEntity("e-1")
            }
        val response = call(filter(), context = signed(), chain = refusing)
        response.status shouldBe 403
        response.contentAsString.contains("SRT-004") shouldBe true
        fields()["result"] shouldBe "REFUSED_ENTITY"
        appender.list.clear()
        val wrapping =
            FilterChain { _, _ ->
                throw ServletException("Request processing failed", FlowContextRefusedException("entity"))
            }
        call(filter(), context = signed(), chain = wrapping).status shouldBe 403
        val failing = FilterChain { _, _ -> error("boom") }
        shouldThrow<IllegalStateException> { call(filter(), context = signed(), chain = failing) }
        shouldThrow<IllegalStateException> { tenants.current() }
    }

    @Test
    fun `INV-AC-8251 an accepted call logs one line whose fields equal the context and never the signature`() {
        val header = signed()
        call(filter(), context = header)
        fields() shouldBe
            mapOf(
                "callerService" to CALLER,
                "flowId" to "flow-7",
                "tenant" to T1,
                "entityKind" to "ENTITY",
                "entityRef" to "E-1",
                "principalKind" to "HUMAN",
                "operation" to "POST /v1/closeTenant",
                "result" to "ACCEPTED",
            )
        appender.list.joinToString { it.formattedMessage } shouldNotContain header.substringAfterLast('.')
        appender.list.size shouldBe 1
    }

    @Test
    fun `INV-AC-8251 a refused call logs one line with the verified caller and the empty context fields`() {
        call(filter())
        fields() shouldBe
            mapOf(
                "callerService" to CALLER,
                "flowId" to "",
                "tenant" to "",
                "entityKind" to "",
                "entityRef" to "",
                "principalKind" to "",
                "operation" to "POST /v1/closeTenant",
                "result" to "REFUSED_NO_CONTEXT",
            )
    }

    @Test
    fun `INV-AC-8251 a tenant-wide context logs an empty entity ref`() {
        call(filter(), context = signed(entity = FlowEntity.tenantWide()))
        fields()["entityKind"] shouldBe "TENANT_WIDE"
        fields()["entityRef"] shouldBe ""
    }

    @Test
    fun `INV-AC-8251 a changed header line, a malformed row or a repeated operationId stops the start-up`() {
        fun failure(text: String) =
            shouldThrow<IllegalStateException> { FlowContextOperations.of(listOf("t" to text)) }.message.orEmpty()
        failure("operationId\tpath\tmethod\ncloseTenant\tPOST\t/v1/a\n") shouldNotContain "closeTenant"
        failure("operationId\tmethod\tpath\ncloseTenant\tPOST\n") shouldNotContain "closeTenant"
        failure("operationId\tmethod\tpath\ncloseTenant\tpost\t/v1/a\n") shouldNotContain "closeTenant"
        failure("operationId\tmethod\tpath\ncloseTenant\tPOST\tv1/a\n") shouldNotContain "closeTenant"
        failure("operationId\tmethod\tpath\na\tPOST\t/v1/a\na\tPOST\t/v1/b\n").contains("line 3") shouldBe true
        failure("").contains("line 1") shouldBe true
        FlowContextOperations.of(listOf("t" to "operationId\tmethod\tpath\n")).serving("POST", "/v1/a") shouldBe null
        FlowContextOperations.NONE.serving("POST", "/v1/a") shouldBe null
    }
}
