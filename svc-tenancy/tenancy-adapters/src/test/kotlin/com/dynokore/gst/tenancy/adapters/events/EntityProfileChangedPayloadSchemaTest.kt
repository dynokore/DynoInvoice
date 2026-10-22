package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.platform.productruntime.ProductEventTypes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate
import java.util.UUID

/** The channel EntityProfileChanged: the payload of a published event validates against its schema (INV-Q-7391). */
class EntityProfileChangedPayloadSchemaTest {
    private val schema = SchemaFixture.schema("entity-profile-changed-payload.schema.json")
    private val recording = RecordingOutbox()
    private val outbox = EntityProfileChangedOutbox(recording, JsonMapper.builder().build())
    private val effective = LocalDate.of(2026, 7, 1)

    private fun change(
        attribute: String,
        old: String?,
        new: String,
    ) = EntityProfileChange(
        tenant = UUID.fromString("0a000000-0000-4000-8000-00000000000a"),
        entityRef = "ENT-1",
        changedAttributes = listOf(ChangedAttribute(attribute, old, new)),
        effectiveDate = effective,
        node =
            StructureNodeState(
                NodeType.ENTITY,
                "GRP-1",
                false,
                "Acme Pte Ltd",
                "ACME",
                9,
                uen = "201912345K",
                profile = listOf(ProfileEntry(attribute, new, effective)),
            ),
    )

    private val published =
        mapOf(
            "partiallyExempt" to change("partiallyExempt", "false", "true"),
            "excludedBusinessStatus" to change("excludedBusinessStatus", null, "OVERSEAS_S33_1"),
            "obligationStartDate" to change("obligationStartDate", "2028-04-01", "2027-01-01"),
        )

    @Test
    fun `the payload of an event for each status attribute validates against the schema`() {
        for ((attribute, change) in published) {
            val payload = outbox.payload(change)
            SchemaFixture.errors(schema, payload.toString()).shouldBeEmpty()
            payload["changedAttributes"][0]["attribute"].asString() shouldBe attribute
        }
    }

    @Test
    fun `the schema refuses the same payload without effectiveDate`() {
        for (change in published.values) {
            val payload = outbox.payload(change)
            SchemaFixture.errors(schema, payload.toString()).shouldBeEmpty()
            payload.remove("effectiveDate")
            SchemaFixture.errors(schema, payload.toString()).shouldNotBeEmpty()
        }
    }

    @Test
    fun `the schema refuses a payload with another required member removed`() {
        val good = outbox.payload(published.getValue("partiallyExempt"))
        for (property in listOf("entityRef", "changedAttributes")) {
            val broken = good.deepCopy().also { it.remove(property) }
            SchemaFixture.errors(schema, broken.toString()).shouldNotBeEmpty()
        }
        for (property in listOf("kind", "retired", "name", "profile", "structureVersion")) {
            val broken = good.deepCopy().also { (it["node"] as ObjectNode).remove(property) }
            SchemaFixture.errors(schema, broken.toString()).shouldNotBeEmpty()
        }
    }

    @Test
    fun `publish appends one event of the catalogued type in the caller's transaction`() {
        outbox.publish(published.getValue("partiallyExempt"))
        recording.events.single().type shouldBe ProductEventTypes.ENTITY_PROFILE_CHANGED
    }
}
