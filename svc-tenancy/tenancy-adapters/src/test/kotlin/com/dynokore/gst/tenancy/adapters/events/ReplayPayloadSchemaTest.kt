package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.ReplayCursor
import com.dynokore.gst.tenancy.adapters.persistence.ReplayNode
import com.dynokore.gst.tenancy.adapters.persistence.ReplayReader
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate

/** The replayed payloads of both sources validate against the published schemas (INV-AC-9089, INV-Q-11719 A). */
class ReplayPayloadSchemaTest {
    private val structureSchema = SchemaFixture.schema("organisation-structure-changed-payload.schema.json")
    private val profileSchema = SchemaFixture.schema("entity-profile-changed-payload.schema.json")
    private val mapper = JsonMapper.builder().build()
    private val effective = LocalDate.of(2026, 7, 1)

    private val group =
        ReplayNode(
            NodeType.GROUP,
            "GRP-1",
            "GRP-1",
            StructureNodeState(NodeType.GROUP, "TEN-1", false, "Group", null, 3, gstn = "M2-1"),
            null,
        )
    private val entity =
        ReplayNode(
            NodeType.ENTITY,
            "ENT-1",
            "ENT-1",
            StructureNodeState(
                NodeType.ENTITY,
                "GRP-1",
                false,
                "Acme Pte Ltd",
                "ACME",
                9,
                uen = "201912345K",
                profile = listOf(ProfileEntry("partiallyExempt", "true", effective)),
            ),
            effective,
        )
    private val unit =
        ReplayNode(
            NodeType.BUSINESS_UNIT,
            "BU-1",
            "ENT-1",
            StructureNodeState(NodeType.BUSINESS_UNIT, "ENT-1", false, "Unit", "ACMEU", 10),
            null,
        )
    private val retired =
        ReplayNode(
            NodeType.ENTITY,
            "ENT-2",
            "ENT-2",
            StructureNodeState(NodeType.ENTITY, "GRP-1", true, "Gone Pte Ltd", "GONE", 11, uen = "201912346K"),
            null,
        )
    private val all = listOf(group, entity, unit, retired)

    private val reader =
        object : ReplayReader {
            override fun tenantIds(): List<TenantId> = emptyList()

            override fun structure(
                scope: ReplayScope?,
                after: ReplayCursor?,
                limit: Int,
            ) = all.take(limit)

            override fun profiles(
                scope: ReplayScope?,
                after: ReplayCursor?,
                limit: Int,
            ) = listOf(entity).take(limit)
        }
    private val structure = OrganisationStructureReplaySource(reader, mapper)
    private val profile = EntityProfileReplaySource(reader, mapper)

    @Test
    fun `INV-AC-9089 every replayed payload validates against the published schemas`() {
        val states = structure.page(null, null, 10).items
        states.size shouldBe 4
        for (state in states) {
            state.type shouldBe ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED
            SchemaFixture.errors(structureSchema, state.dataJson).shouldBeEmpty()
        }
        val retiredPayload = mapper.readTree(states.last().dataJson)
        retiredPayload["changeType"].asString() shouldBe "RETIRED"
        retiredPayload["node"]["retired"].asBoolean() shouldBe true
        mapper.readTree(states.first().dataJson)["changeType"].asString() shouldBe "UPDATED"

        val profiles = profile.page(null, null, 10).items
        profiles.size shouldBe 1
        profiles.single().type shouldBe ProductEventTypes.ENTITY_PROFILE_CHANGED
        SchemaFixture.errors(profileSchema, profiles.single().dataJson).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-9089 the schemas refuse a replayed payload with a required member removed`() {
        val good = mapper.readTree(structure.page(null, null, 10).items[1].dataJson) as ObjectNode
        for (property in listOf("nodeType", "nodeRef", "changeType")) {
            val broken = good.deepCopy().also { it.remove(property) }
            SchemaFixture.errors(structureSchema, broken.toString()).shouldNotBeEmpty()
        }
        val goodProfile =
            mapper.readTree(
                profile
                    .page(null, null, 10)
                    .items
                    .single()
                    .dataJson,
            ) as ObjectNode
        for (property in listOf("entityRef", "changedAttributes", "effectiveDate")) {
            val broken = goodProfile.deepCopy().also { it.remove(property) }
            SchemaFixture.errors(profileSchema, broken.toString()).shouldNotBeEmpty()
        }
    }
}
