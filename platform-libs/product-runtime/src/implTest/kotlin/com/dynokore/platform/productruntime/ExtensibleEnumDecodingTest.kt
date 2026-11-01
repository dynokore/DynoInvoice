package com.dynokore.platform.productruntime

import com.dynokore.gst.productRuntimeTenancyClient.adapters.clients.api.EntitiesApi
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.fallback.TenancyStructureFallback
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.Lookup
import com.dynokore.platform.serviceruntime.replica.StructureKey
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * INV-T-12576 (INV-AC-12574): an unlisted value of an extensible enum (`kind` is `x-extensible-enum: true`
 * in the tenancy structure contracts) decodes to UNKNOWN through the structure feed and the tenancy
 * fallback (INV-Q-12569 A, INV-Q-12571 A); a closed requirement (`kind` missing) stays a ContractViolation.
 */
class ExtensibleEnumDecodingTest {
    private val deadline = Deadline.after(Duration.ofMillis(500), CLOCK)

    private fun node(kindMember: String?) =
        """{${kindMember?.let { """"kind":"$it",""" } ?: ""}"parentRef":"G-1","retired":false,"name":"Acme",
        |"code":"E1","profile":[],"structureVersion":5}
        """.trimMargin()

    private fun fed(kindMember: String?): StructureNode {
        val replica = StructureReplica(CLOCK)
        StructureFeedHandler(replica).handle(
            envelope(
                ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED,
                """{"nodeType":"ENTITY","nodeRef":"E-001","changeType":"UPDATED","parentRef":"G-1",
                |"node":${node(kindMember)}}
                """.trimMargin(),
            ),
        )
        replica.onSnapshotComplete(5)
        return (replica.node(TENANT, "E-001", Deadline.after(Duration.ofSeconds(1), CLOCK)) as Lookup.Local).value
    }

    private fun fallen(kindMember: String): StructureNode {
        val builder = RestClient.builder().baseUrl("http://svc")
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(requestTo("http://svc/internal/v1/tenancy/entities/E-001"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"entityRef":"E-001","legalName":"Acme","lifecycleState":"LIVE","node":${node(kindMember)}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val result =
            TenancyStructureFallback(
                EntitiesApi(builder.build()),
            ).fetch(StructureKey(TENANT, "E-001"), deadline)
        return result.shouldBeInstanceOf<FallbackResult.Found<StructureNode>>().value
    }

    @Test
    fun `the feed decodes the unlisted kind WAREHOUSE to UNKNOWN`() {
        fed("WAREHOUSE").kind shouldBe StructureNodeKind.UNKNOWN
    }

    @Test
    fun `the fallback decodes the unlisted kind WAREHOUSE to UNKNOWN`() {
        fallen("WAREHOUSE").kind shouldBe StructureNodeKind.UNKNOWN
    }

    @Test
    fun `the feed keeps the listed kind ENTITY`() {
        fed("ENTITY").kind shouldBe StructureNodeKind.ENTITY
    }

    @Test
    fun `the fallback keeps the listed kind ENTITY`() {
        fallen("ENTITY").kind shouldBe StructureNodeKind.ENTITY
    }

    @Test
    fun `a feed node without the required kind is still a ContractViolation`() {
        assertThrows<ContractViolation> { fed(null) }
    }
}
