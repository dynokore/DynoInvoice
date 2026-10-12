package com.dynokore.platform.productruntime

import com.dynokore.gst.productRuntimeAccessClient.adapters.clients.api.AuthoriseApi
import com.dynokore.gst.productRuntimeTenancyClient.adapters.clients.api.EntitiesApi
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.fallback.AccessAuthorityFallback
import com.dynokore.platform.productruntime.fallback.TenancyStructureFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureKey
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.resilience.Deadline
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

/** INV-T-2876: the tenancy-svc and access-svc fallbacks over the generated clients (INV-REQ-1200 b2, INV-REQ-1201 b3). */
class FallbacksTest {
    private val deadline = Deadline.after(Duration.ofMillis(500), CLOCK)
    private val builder = RestClient.builder().baseUrl("http://svc")
    private val server = MockRestServiceServer.bindTo(builder).build()

    @Test
    fun `the tenancy fallback reads the node from the extended getEntityStructure`() {
        server.expect(requestTo("http://svc/internal/v1/tenancy/entities/E-001")).andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"entityRef":"E-001","legalName":"Acme","lifecycleState":"LIVE","node":{"kind":"ENTITY",
                    |"retired":false,"name":"Acme","profile":[],"structureVersion":9,"parentRef":"G-1","code":"E1"}}
                    """.trimMargin(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        val result = TenancyStructureFallback(EntitiesApi(builder.build())).fetch(StructureKey(TENANT, "E-001"), deadline)
        val node = result.shouldBeInstanceOf<FallbackResult.Found<StructureNode>>().value
        node.structureVersion shouldBe 9
        node.parentRef shouldBe "G-1"
        server.verify()
    }

    @Test
    fun `a 404 from tenancy-svc is NotFound, a response without node is Unavailable`() {
        server.expect(requestTo("http://svc/internal/v1/tenancy/entities/E-404"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{}"))
        server.expect(requestTo("http://svc/internal/v1/tenancy/entities/E-002"))
            .andRespond(withSuccess("""{"entityRef":"E-002","legalName":"B","lifecycleState":"LIVE"}""", MediaType.APPLICATION_JSON))
        val fallback = TenancyStructureFallback(EntitiesApi(builder.build()))
        fallback.fetch(StructureKey(TENANT, "E-404"), deadline) shouldBe FallbackResult.NotFound
        fallback.fetch(StructureKey(TENANT, "E-002"), deadline) shouldBe FallbackResult.Unavailable
    }

    @Test
    fun `the access fallback asks authorise for the principal, the action and the entity`() {
        server.expect(requestTo("http://svc/internal/v1/access/authorise?principalRef=P-1&permission=document.read&scope=E-001"))
            .andRespond(withSuccess("""{"decision":"ALLOW","role":"tax.approver"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("http://svc/internal/v1/access/authorise?principalRef=P-1&permission=document.void&scope=E-001"))
            .andRespond(withSuccess("""{"decision":"DENY"}""", MediaType.APPLICATION_JSON))
        val fallback = AccessAuthorityFallback(AuthoriseApi(builder.build()))
        val principal = Principal("P-1", PrincipalKind.HUMAN)
        fallback.fetch(AuthorizationRequest(TENANT, principal, "E-001", "document.read"), deadline) shouldBe
            FallbackResult.Found(AuthorityVerdict(true))
        fallback.fetch(AuthorizationRequest(TENANT, principal, "E-001", "document.void"), deadline) shouldBe
            FallbackResult.Found(AuthorityVerdict(false))
        server.verify()
    }
}
