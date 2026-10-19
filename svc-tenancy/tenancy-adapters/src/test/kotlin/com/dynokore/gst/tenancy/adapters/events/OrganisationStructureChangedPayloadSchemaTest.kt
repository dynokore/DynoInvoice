package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.platform.productruntime.ProductEventTypes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The channel OrganisationStructureChanged: the payload this service writes validates against its schema. */
class OrganisationStructureChangedPayloadSchemaTest {
    private val schema = SchemaFixture.schema("organisation-structure-changed-payload.schema.json")
    private val recording = RecordingOutbox()
    private val outbox = OrganisationStructureChangedOutbox(recording, JsonMapper.builder().build())

    private fun change(
        type: NodeType,
        change: ChangeType,
        code: String? = null,
        parent: String? = "PARENT-1",
        previous: String? = null,
    ) = StructureChange(
        tenant = UUID.fromString("0a000000-0000-4000-8000-00000000000a"),
        entityRef = "ENT-1",
        nodeType = type,
        nodeRef = "NODE-1",
        changeType = change,
        parentRef = parent,
        previousParentRef = previous,
        node = StructureNodeState(type, parent, false, "A name", code, 7),
    )

    @Test
    fun `every node type and change type the service writes validates against the schema`() {
        for (type in NodeType.entries) {
            for (kind in ChangeType.entries) {
                val code = if (type == NodeType.ENTITY || type == NodeType.BUSINESS_UNIT) "AB12" else null
                val payload = outbox.payload(change(type, kind, code, previous = "PREV-1")).toString()
                SchemaFixture.errors(schema, payload).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `publish appends one event of the catalogued type in the caller's transaction`() {
        outbox.publish(change(NodeType.BUSINESS_UNIT, ChangeType.MOVED, "BU1", previous = "ENT-0"))
        recording.events.single().type shouldBe ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED
    }

    @Test
    fun `the schema refuses a payload with a required property removed`() {
        val good = outbox.payload(change(NodeType.ENTITY, ChangeType.CREATED, "AB12"))
        for (property in listOf("nodeType", "nodeRef", "changeType")) {
            val broken = good.deepCopy().also { it.remove(property) }
            SchemaFixture.errors(schema, broken.toString()).shouldNotBeEmpty()
        }
        for (property in listOf("kind", "retired", "name", "profile", "structureVersion")) {
            val broken =
                good.deepCopy().also {
                    (it["node"] as tools.jackson.databind.node.ObjectNode).remove(
                        property,
                    )
                }
            SchemaFixture.errors(schema, broken.toString()).shouldNotBeEmpty()
        }
    }
}
