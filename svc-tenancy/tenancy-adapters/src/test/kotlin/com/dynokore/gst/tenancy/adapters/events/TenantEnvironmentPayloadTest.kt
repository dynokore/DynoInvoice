package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.http.nodeOf
import com.dynokore.gst.tenancy.adapters.persistence.ReplayNode
import com.dynokore.gst.tenancy.adapters.persistence.ReplayReader
import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.application.structure.tenantNode
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.platform.outboxandqueue.EntityRef
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** INV-Q-9032 A: the TENANT node carries the stored environment in the body, the live event and the replay. */
class TenantEnvironmentPayloadTest {
    private val mapper = JsonMapper.builder().build()
    private val schema = SchemaFixture.schema("organisation-structure-changed-payload.schema.json")
    private val outbox = OrganisationStructureChangedOutbox(RecordingOutbox(), mapper)
    private val replay =
        OrganisationStructureReplaySource(
            object : ReplayReader {
                override fun tenantIds() = emptyList<com.dynokore.platform.tenancykernel.TenantId>()

                override fun structure(
                    scope: com.dynokore.platform.outboxandqueue.ReplayScope?,
                    after: com.dynokore.gst.tenancy.adapters.persistence.ReplayCursor?,
                    limit: Int,
                ) = emptyList<ReplayNode>()

                override fun profiles(
                    scope: com.dynokore.platform.outboxandqueue.ReplayScope?,
                    after: com.dynokore.gst.tenancy.adapters.persistence.ReplayCursor?,
                    limit: Int,
                ) = emptyList<ReplayNode>()
            },
            mapper,
        )

    private fun tenant(
        name: String,
        environment: Environment?,
        retired: Boolean = false,
        version: Long = 4,
    ) = StructureNodeState(NodeType.TENANT, null, retired, name, null, version, environment = environment)

    private fun live(node: StructureNodeState) =
        outbox
            .payload(
                StructureChange(
                    UUID.fromString("0a000000-0000-4000-8000-00000000000a"),
                    "T-1",
                    node.kind,
                    "T-1",
                    ChangeType.UPDATED,
                    node.parentRef,
                    null,
                    node,
                ),
            )["node"]

    private fun replayed(node: StructureNodeState) =
        replay.payload(ReplayNode(node.kind, "T-1", "T-1", node, null))["node"]

    @Test
    fun `a workspace tenant and its production sibling Acme and Acme-sandbox each carry their own value`() {
        live(tenant("Acme", Environment.PRODUCTION))["environment"].asString() shouldBe "PRODUCTION"
        live(tenant("Acme-sandbox", Environment.SANDBOX))["environment"].asString() shouldBe "SANDBOX"
        nodeOf(tenant("Acme", Environment.PRODUCTION)).environment?.value shouldBe "PRODUCTION"
        nodeOf(tenant("Acme-sandbox", Environment.SANDBOX)).environment?.value shouldBe "SANDBOX"
    }

    @Test
    fun `a later retired SANDBOX tenant state with a higher structureVersion keeps SANDBOX`() {
        val later = tenant("Acme-sandbox", Environment.SANDBOX, retired = true, version = 9)
        live(later)["environment"].asString() shouldBe "SANDBOX"
        live(later)["structureVersion"].asLong() shouldBe 9
        replayed(later)["environment"].asString() shouldBe "SANDBOX"
    }

    @Test
    fun `the replayed node equals the live node for the same tenant`() {
        for (environment in listOf(Environment.SANDBOX, Environment.PRODUCTION)) {
            val node = tenant("Acme", environment)
            replayed(node).toString() shouldBe live(node).toString()
        }
    }

    @Test
    fun `a tenant whose stored attribute is missing carries no environment`() {
        val node = tenant("Unknown", null)
        live(node).has("environment") shouldBe false
        replayed(node).has("environment") shouldBe false
        nodeOf(node).environment shouldBe null
    }

    @Test
    fun `an ENTITY node of a SANDBOX tenant has no environment`() {
        val entity = StructureNodeState(NodeType.ENTITY, "G", false, "Acme Pte Ltd", "AB12", 5)
        live(entity).has("environment") shouldBe false
        replayed(entity).has("environment") shouldBe false
    }

    @Test
    fun `INV-AC-9149 the tenant node payloads validate against the published schema`() {
        for (environment in listOf(Environment.SANDBOX, Environment.PRODUCTION, null)) {
            val node = tenant("Acme", environment)
            for (nodeJson in listOf(live(node), replayed(node))) {
                val root = mapper.createObjectNode()
                root.put("nodeType", "TENANT")
                root.put("nodeRef", "T-1")
                root.put("changeType", "UPDATED")
                root.set("node", nodeJson)
                SchemaFixture.errors(schema, root.toString()).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `INV-AC-15381 the replayed tenant state carries the shared node, the tenant ref and the stored version`() {
        val row =
            TenantRecord(
                UUID.randomUUID(),
                "Acme-sandbox",
                "Acme Legal",
                "plan",
                "c",
                9,
                true,
                null,
                Environment.SANDBOX,
            )
        val node = tenantNode(row)
        val state = replay.state(ReplayNode(node.kind, row.ref, row.ref, node, null))
        state.entity shouldBe EntityRef("Acme-sandbox")
        state.aggregateKey shouldBe "Acme-sandbox"
        state.docSequence shouldBe null
        val data = mapper.readTree(state.dataJson)
        data["nodeType"].asString() shouldBe "TENANT"
        data["nodeRef"].asString() shouldBe "Acme-sandbox"
        data["changeType"].asString() shouldBe "RETIRED"
        data.has("parentRef") shouldBe false
        data.has("previousParentRef") shouldBe false
        data["node"]["structureVersion"].asLong() shouldBe 9
        data["node"]["environment"].asString() shouldBe "SANDBOX"
        data["node"]["name"].asString() shouldBe "Acme Legal"
        data["node"].has("code") shouldBe false
        data["node"]["profile"].size() shouldBe 0
        nodeOf(node).environment?.value shouldBe "SANDBOX"
        SchemaFixture.errors(schema, state.dataJson).shouldBeEmpty()
        val live = tenantNode(row.copy(retired = false, ref = "Acme", environment = Environment.PRODUCTION))
        mapper
            .readTree(replay.state(ReplayNode(live.kind, "Acme", "Acme", live, null)).dataJson)["changeType"]
            .asString() shouldBe "UPDATED"
    }
}
