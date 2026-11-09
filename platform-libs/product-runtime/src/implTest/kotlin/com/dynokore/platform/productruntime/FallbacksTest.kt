package com.dynokore.platform.productruntime

import com.dynokore.gst.productRuntimeAccessClient.adapters.clients.api.AuthoriseApi
import com.dynokore.gst.productRuntimeTenancyClient.adapters.clients.api.EntitiesApi
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.fallback.AccessAuthorityFallback
import com.dynokore.platform.productruntime.fallback.TenancyStructureFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureEnvironment
import com.dynokore.platform.serviceruntime.replica.StructureKey
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.resilience.Deadline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * INV-T-2876: the tenancy-svc and access-svc fallbacks over the generated clients (INV-REQ-1200 b2, INV-REQ-1201 b3).
 */
class FallbacksTest {
    private val deadline = Deadline.after(Duration.ofMillis(500), CLOCK)
    private val builder = RestClient.builder().baseUrl("http://svc")
    private val server = MockRestServiceServer.bindTo(builder).build()

    @Test
    fun `the tenancy fallback reads the node from the extended getEntityStructure`() {
        server
            .expect(requestTo("http://svc/internal/v1/tenancy/entities/E-001"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"entityRef":"E-001","legalName":"Acme","lifecycleState":"LIVE","node":{"kind":"ENTITY",
                    |"retired":false,"name":"Acme","profile":[],"structureVersion":9,"parentRef":"G-1","code":"E1"}}
                    """.trimMargin(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        val result =
            TenancyStructureFallback(
                EntitiesApi(builder.build()),
            ).fetch(StructureKey(TENANT, "E-001"), deadline)
        val node = result.shouldBeInstanceOf<FallbackResult.Found<StructureNode>>().value
        node.structureVersion shouldBe 9
        node.parentRef shouldBe "G-1"
        server.verify()
    }

    private fun environmentFrom(environment: String?): StructureEnvironment? {
        val field = environment?.let { ""","environment":"$it"""" } ?: ""
        server
            .expect(requestTo("http://svc/internal/v1/tenancy/entities/E-001"))
            .andRespond(
                withSuccess(
                    """{"entityRef":"E-001","legalName":"Acme","lifecycleState":"LIVE","node":{"kind":"TENANT",
                    |"retired":false,"name":"Acme","profile":[],"structureVersion":9$field}}
                    """.trimMargin(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        val result =
            TenancyStructureFallback(EntitiesApi(builder.build())).fetch(StructureKey(TENANT, "E-001"), deadline)
        return result.shouldBeInstanceOf<FallbackResult.Found<StructureNode>>().value.environment
    }

    @Test
    fun `the tenancy fallback reads SANDBOX`() {
        environmentFrom("SANDBOX") shouldBe StructureEnvironment.SANDBOX
    }

    @Test
    fun `the tenancy fallback reads PRODUCTION`() {
        environmentFrom("PRODUCTION") shouldBe StructureEnvironment.PRODUCTION
    }

    @Test
    fun `the tenancy fallback gives null for an absent environment`() {
        environmentFrom(null) shouldBe null
    }

    @Test
    fun `the tenancy fallback gives UNKNOWN for sandbox`() {
        environmentFrom("sandbox") shouldBe StructureEnvironment.UNKNOWN
    }

    @Test
    fun `a 404 from tenancy-svc is NotFound, a response without node is Unavailable`() {
        server
            .expect(requestTo("http://svc/internal/v1/tenancy/entities/E-404"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{}"))
        server
            .expect(requestTo("http://svc/internal/v1/tenancy/entities/E-002"))
            .andRespond(
                withSuccess(
                    """{"entityRef":"E-002","legalName":"B","lifecycleState":"LIVE"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val fallback = TenancyStructureFallback(EntitiesApi(builder.build()))
        fallback.fetch(StructureKey(TENANT, "E-404"), deadline) shouldBe FallbackResult.NotFound
        fallback.fetch(StructureKey(TENANT, "E-002"), deadline) shouldBe FallbackResult.Unavailable
    }

    @Test
    fun `INV-AC-19208 the access fallback asks authorise for the principal, the action and the entity`() {
        server
            .expect(
                requestTo(
                    "http://svc/internal/v1/access/authorise?principalRef=P-1&permission=document.read&scope=entity:E-001",
                ),
            ).andRespond(withSuccess("""{"decision":"ALLOW","role":"tax.approver"}""", MediaType.APPLICATION_JSON))
        server
            .expect(
                requestTo(
                    "http://svc/internal/v1/access/authorise?principalRef=P-1&permission=document.void&scope=entity:E-001",
                ),
            ).andRespond(withSuccess("""{"decision":"DENY"}""", MediaType.APPLICATION_JSON))
        val fallback = AccessAuthorityFallback(AuthoriseApi(builder.build()))
        val principal = Principal("P-1", PrincipalKind.HUMAN)
        fallback.fetch(AuthorizationRequest(TENANT, principal, "E-001", "document.read"), deadline) shouldBe
            FallbackResult.Found(AuthorityVerdict(true))
        fallback.fetch(AuthorizationRequest(TENANT, principal, "E-001", "document.void"), deadline) shouldBe
            FallbackResult.Found(AuthorityVerdict(false))
        server.verify()
    }

    private fun request(
        entityRef: String,
        level: ScopeLevel = ScopeLevel.ENTITY,
    ) = AuthorizationRequest(TENANT, Principal("P-1", PrincipalKind.HUMAN), entityRef, "document.read", level = level)

    @Test
    fun `INV-AC-19208 a blank entityRef returns Unavailable and sends no request`() {
        val fallback = AccessAuthorityFallback(AuthoriseApi(builder.build()))
        fallback.fetch(request(""), deadline) shouldBe FallbackResult.Unavailable
        fallback.fetch(request("  "), deadline) shouldBe FallbackResult.Unavailable
        server.verify()
    }

    @Test
    fun `INV-AC-19208 every level other than ENTITY returns Unavailable and sends no request`() {
        val fallback = AccessAuthorityFallback(AuthoriseApi(builder.build()))
        listOf(
            ScopeLevel.TENANT,
            ScopeLevel.GROUP,
            ScopeLevel.INSTALLATION,
            ScopeLevel.FEED,
            ScopeLevel.AP_CONNECTION,
            ScopeLevel.CLIENT_ENTITY,
        ).forEach { level ->
            fallback.fetch(request("E-001", level), deadline) shouldBe FallbackResult.Unavailable
        }
        server.verify()
    }

    @Test
    fun `INV-AC-19208 a mock expecting the bare scope is not met`() {
        server
            .expect(
                requestTo(
                    "http://svc/internal/v1/access/authorise?principalRef=P-1&permission=document.read&scope=E-001",
                ),
            ).andRespond(withSuccess("""{"decision":"ALLOW"}""", MediaType.APPLICATION_JSON))
        val fallback = AccessAuthorityFallback(AuthoriseApi(builder.build()))
        shouldThrow<AssertionError> { fallback.fetch(request("E-001"), deadline) }
    }
}
