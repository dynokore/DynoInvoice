package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
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
    fun `the Relay to Observe event validates and the payload without changedAttributes is refused`() {
        val later = LocalDate.of(2026, 9, 1)
        val change =
            EntityProfileChange(
                tenant = UUID.fromString("0a000000-0000-4000-8000-00000000000a"),
                entityRef = "ENT-1",
                changedAttributes = listOf(ChangedAttribute("issuanceMode", "RELAY", "OBSERVE")),
                effectiveDate = later,
                node =
                    StructureNodeState(
                        NodeType.ENTITY,
                        "GRP-1",
                        false,
                        "Acme Pte Ltd",
                        "ACME",
                        11,
                        uen = "201912345K",
                        profile =
                            listOf(
                                ProfileEntry("issuanceMode", "RELAY", effective),
                                ProfileEntry("issuanceMode", "OBSERVE", later),
                            ),
                    ),
            )
        val payload = outbox.payload(change)
        SchemaFixture.errors(schema, payload.toString()).shouldBeEmpty()
        payload["changedAttributes"][0]["attribute"].asString() shouldBe "issuanceMode"
        payload["node"]["profile"].size() shouldBe 2
        payload["node"]["structureVersion"].asLong() shouldBe 11
        payload.remove("changedAttributes")
        SchemaFixture.errors(schema, payload.toString()).shouldNotBeEmpty()
    }

    @Test
    fun `publish appends one event of the catalogued type in the caller's transaction`() {
        outbox.publish(published.getValue("partiallyExempt"))
        recording.events.single().type shouldBe ProductEventTypes.ENTITY_PROFILE_CHANGED
    }

    /** The scheme keys of the catalogue row VP-53, read from the catalogue (never listed here). */
    private fun schemeKeys(): List<String> =
        checkNotNull(GstVariationPointCatalogue().find(VariationPointKey("VP-53")))
            .options
            .map { it.key }
            .filter { it != "NONE" }

    private fun schemeChange(
        attributes: List<ChangedAttribute>,
        profile: List<ProfileEntry>,
    ) = EntityProfileChange(
        tenant = UUID.fromString("0a000000-0000-4000-8000-00000000000a"),
        entityRef = "ENT-1",
        changedAttributes = attributes,
        effectiveDate = effective,
        node =
            StructureNodeState(
                NodeType.ENTITY,
                "GRP-1",
                false,
                "Acme Pte Ltd",
                "ACME",
                12,
                uen = "201912345K",
                profile = profile,
            ),
    )

    /** The events of a first selection of two keys, a reduction to one and the selection NONE (probes (a) to (c)). */
    private fun schemeEvents(
        first: String,
        second: String,
    ): List<EntityProfileChange> {
        val later = effective.plusDays(1)
        val last = effective.plusDays(2)
        val firstEvent =
            schemeChange(
                listOf(
                    ChangedAttribute("gstScheme.$first", null, "ON"),
                    ChangedAttribute("gstScheme.$second", null, "ON"),
                ),
                listOf(
                    ProfileEntry("gstScheme.$first", "ON", effective),
                    ProfileEntry("gstScheme.$second", "ON", effective),
                ),
            )
        val reduced =
            schemeChange(
                listOf(ChangedAttribute("gstScheme.$second", "ON", "OFF")),
                listOf(
                    ProfileEntry("gstScheme.$first", "ON", effective),
                    ProfileEntry("gstScheme.$second", "ON", effective),
                    ProfileEntry("gstScheme.$second", "OFF", later),
                ),
            )
        val none =
            schemeChange(
                listOf(ChangedAttribute("gstScheme.$first", "ON", "OFF")),
                listOf(
                    ProfileEntry("gstScheme.$first", "ON", effective),
                    ProfileEntry("gstScheme.$first", "OFF", last),
                    ProfileEntry("gstScheme.$second", "ON", effective),
                    ProfileEntry("gstScheme.$second", "OFF", later),
                ),
            )
        return listOf(firstEvent, reduced, none)
    }

    @Test
    fun `the gstScheme events validate and the first one without changedAttributes is refused`() {
        val keys = schemeKeys()
        keys.size shouldBe 14
        val (first, second) = keys
        val events = schemeEvents(first, second)
        for (change in events) {
            val payload = outbox.payload(change)
            SchemaFixture.errors(schema, payload.toString()).shouldBeEmpty()
            payload["changedAttributes"].forEach { (it["attribute"].asString().startsWith("gstScheme.")) shouldBe true }
        }
        val payload = outbox.payload(events.first())
        payload["changedAttributes"].size() shouldBe 2
        payload["changedAttributes"][0].has("oldValue") shouldBe false
        payload.remove("changedAttributes")
        SchemaFixture.errors(schema, payload.toString()).shouldNotBeEmpty()
    }
}
