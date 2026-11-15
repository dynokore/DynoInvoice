package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.ReplayCursor
import com.dynokore.gst.tenancy.adapters.persistence.ReplayNode
import com.dynokore.gst.tenancy.adapters.persistence.ReplayReader
import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate
import java.util.UUID

/** INV-AC-21394: the entity constitution is the member constitution of EntityProfileChanged, live and replayed. */
class EntityProfileConstitutionTest {
    private val mapper = JsonMapper.builder().build()
    private val schema = SchemaFixture.schema("entity-profile-changed-payload.schema.json")
    private val outbox = EntityProfileChangedOutbox(RecordingOutbox(), mapper)
    private val effective = LocalDate.of(2026, 7, 1)

    private fun live(node: StructureNodeState) =
        EntityProfileChange(
            tenant = UUID.fromString("0a000000-0000-4000-8000-00000000000a"),
            entityRef = "ENT-1",
            changedAttributes = listOf(ChangedAttribute("partiallyExempt", "false", "true")),
            effectiveDate = effective,
            node = node,
        )

    private fun entity(constitution: String?) =
        StructureNodeState(
            NodeType.ENTITY,
            "GRP-1",
            false,
            "Acme Pte Ltd",
            "ACME",
            9,
            uen = "201912345K",
            profile = listOf(ProfileEntry("partiallyExempt", "true", effective)),
            constitution = constitution,
        )

    private val group = StructureNodeState(NodeType.GROUP, "TEN-1", false, "Group", null, 3, gstn = "M2-1")

    private fun replayPayload(): ObjectNode {
        val record =
            EntityRecord(
                UUID.randomUUID(),
                "ENT-1",
                "GRP-1",
                "Acme Pte Ltd",
                "SOLE_PROPRIETOR",
                "201912345K",
                null,
                "ACME",
                "ACTIVE",
                false,
                9,
            )
        val node = entityNode(record, listOf(ProfileEntry("partiallyExempt", "true", effective)))
        val row = ReplayNode(NodeType.ENTITY, "ENT-1", "ENT-1", node, effective)
        return EntityProfileReplaySource(NoReader, mapper).payload(row)
    }

    private fun valid(payload: ObjectNode) = SchemaFixture.errors(schema, payload.toString()).shouldBeEmpty()

    @Test
    fun `INV-AC-21394 live payload carries PRIVATE_LIMITED as the top-level constitution`() {
        val payload = outbox.payload(live(entity("PRIVATE_LIMITED")))
        payload["constitution"].asString() shouldBe "PRIVATE_LIMITED"
        payload["node"].has("constitution") shouldBe false
        payload["node"]["structureVersion"].asLong() shouldBe 9
        valid(payload)
    }

    @Test
    fun `INV-AC-21394 live payload carries PARTNERSHIP as the top-level constitution`() {
        val payload = outbox.payload(live(entity("PARTNERSHIP")))
        payload["constitution"].asString() shouldBe "PARTNERSHIP"
        valid(payload)
    }

    @Test
    fun `INV-AC-21394 live payload of a GROUP node has no constitution key`() {
        val payload = outbox.payload(live(group))
        payload.has("constitution") shouldBe false
        valid(payload)
    }

    @Test
    fun `INV-AC-21394 replay payload carries SOLE_PROPRIETOR and no constitution inside node`() {
        val payload = replayPayload()
        payload["constitution"].asString() shouldBe "SOLE_PROPRIETOR"
        payload["node"].has("constitution") shouldBe false
        valid(payload)
    }

    @Test
    fun `INV-AC-21394 the other members equal the payload without a constitution`() {
        val withIt = outbox.payload(live(entity("PRIVATE_LIMITED")))
        val without = outbox.payload(live(entity(null)))
        without.has("constitution") shouldBe false
        withIt.deepCopy().also { it.remove("constitution") } shouldBe without
        withIt.propertyNames().toList() shouldBe
            listOf("entityRef", "changedAttributes", "effectiveDate", "constitution", "node")
    }

    private object NoReader : ReplayReader {
        override fun tenantIds() = emptyList<TenantId>()

        override fun structure(
            scope: ReplayScope?,
            after: ReplayCursor?,
            limit: Int,
        ) = emptyList<ReplayNode>()

        override fun profiles(
            scope: ReplayScope?,
            after: ReplayCursor?,
            limit: Int,
        ) = emptyList<ReplayNode>()
    }
}
