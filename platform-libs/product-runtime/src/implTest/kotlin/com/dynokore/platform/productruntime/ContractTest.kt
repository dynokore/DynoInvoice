package com.dynokore.platform.productruntime

import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayload
import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadConsentRecord
import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadGrantRecord
import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadGrantRecordGrantsInner
import com.dynokore.gst.productRuntimePolicyEvents.adapters.events.model.PolicyChangedPayload
import com.dynokore.gst.productRuntimeProfileEvents.adapters.events.model.EntityProfileChangedPayload
import com.dynokore.gst.productRuntimeProfileEvents.adapters.events.model.EntityProfileChangedPayloadChangedAttributesInner
import com.dynokore.gst.productRuntimeProfileEvents.adapters.events.model.EntityProfileChangedPayloadNode
import com.dynokore.gst.productRuntimeProfileEvents.adapters.events.model.EntityProfileChangedPayloadNodeProfileInner
import com.dynokore.gst.productRuntimeStructureEvents.adapters.events.model.OrganisationStructureChangedPayload
import com.dynokore.gst.productRuntimeStructureEvents.adapters.events.model.OrganisationStructureChangedPayloadNode
import com.dynokore.gst.productRuntimeStructureEvents.adapters.events.model.OrganisationStructureChangedPayloadNodeProfileInner
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.events.PlatformEventTypes
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Contract tests against the generated event types (INV-T-2876 done-when; INV-Q-3852): each payload built from its
 * generated type carries exactly what the pinned platform-contracts schema the generator read requires, and the
 * feed reads it into its replica. PolicyChanged is built from its generated type and read by service-runtime's
 * reader (INV-AC-2148).
 */
class ContractTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    /**
     * The JSON of a generated payload as Jackson's own introspection writes it: the generated `@get:JsonProperty`
     * names and the generated `NON_NULL` inclusion, with no reflection in this test (INV-T-4188).
     */
    private fun tree(value: Any?): JsonNode = mapper.valueToTree(value)

    private fun schema(
        svc: String,
        file: String,
    ): JsonNode {
        val root = File(System.getProperty("productruntime.contracts"))
        val version = System.getProperty("productruntime.contractsVersion")
        return mapper.readTree(File(root, "platform-contracts/$svc/$version/schemas/$file"))
    }

    /** Every property of [json] is one [schema] declares, every required one is present, recursively. */
    private fun conforms(
        json: JsonNode,
        schema: JsonNode,
        path: String = "$",
    ) {
        val properties = schema.get("properties") ?: return
        val declared = properties.propertyNames().toSet()
        val present = json.propertyNames().toSet()
        withPath(path) { declared.shouldContainAll(present) }
        val required = schema.get("required")?.let { r -> (0 until r.size()).map { r.get(it).asString() } }.orEmpty()
        withPath(path) { present.shouldContainAll(required) }
        present.forEach { name ->
            val sub = properties.get(name)
            val value = json.get(name)
            when {
                value.isObject -> {
                    conforms(value, sub, "$path.$name")
                }

                value.isArray && sub.get("items") != null -> {
                    (0 until value.size())
                        .map(value::get)
                        .filter {
                            it.isObject
                        }.forEach { conforms(it, sub.get("items"), "$path.$name[]") }
                }
            }
        }
    }

    private fun withPath(
        path: String,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue(path, block)

    @Test
    fun `OrganisationStructureChanged built from its generated type meets the schema and feeds the replica`() {
        val payload =
            OrganisationStructureChangedPayload(
                nodeType = OrganisationStructureChangedPayload.NodeType.ENTITY,
                nodeRef = "E-001",
                changeType = OrganisationStructureChangedPayload.ChangeType.MOVED,
                parentRef = "G-2",
                previousParentRef = "G-1",
                node =
                    OrganisationStructureChangedPayloadNode(
                        kind = OrganisationStructureChangedPayloadNode.Kind.ENTITY,
                        retired = false,
                        name = "Acme",
                        profile =
                            listOf(
                                OrganisationStructureChangedPayloadNodeProfileInner(
                                    "gstRegNo",
                                    "M9",
                                    LocalDate.parse("2026-01-01"),
                                ),
                            ),
                        structureVersion = 2,
                        parentRef = "G-2",
                    ),
            )
        val json = tree(payload)
        conforms(json, schema("tenancy", "organisation-structure-changed-payload.schema.json"))
        val event =
            StructureFeedHandler(
                StructureReplica(CLOCK),
            ).decode(envelope(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, json.toString()))
        event.node.parentRef shouldBe "G-2"
        event.node.structureVersion shouldBe 2
    }

    @Test
    fun `EntityProfileChanged built from its generated type meets the schema and feeds the replica`() {
        val payload =
            EntityProfileChangedPayload(
                entityRef = "E-001",
                changedAttributes =
                    listOf(
                        EntityProfileChangedPayloadChangedAttributesInner(attribute = "gstRegNo", newValue = "M9"),
                    ),
                effectiveDate = LocalDate.parse("2026-07-01"),
                node =
                    EntityProfileChangedPayloadNode(
                        kind = EntityProfileChangedPayloadNode.Kind.ENTITY,
                        retired = false,
                        name = "Acme",
                        profile =
                            listOf(
                                EntityProfileChangedPayloadNodeProfileInner(
                                    "gstRegNo",
                                    "M9",
                                    LocalDate.parse("2026-07-01"),
                                ),
                            ),
                        structureVersion = 3,
                    ),
            )
        val json = tree(payload)
        conforms(json, schema("tenancy", "entity-profile-changed-payload.schema.json"))
        StructureFeedHandler(StructureReplica(CLOCK))
            .decode(envelope(ProductEventTypes.ENTITY_PROFILE_CHANGED, json.toString()))
            .node
            .attribute("gstRegNo", LocalDate.parse("2026-07-01")) shouldBe "M9"
    }

    @Test
    fun `AuthorityChanged built from its generated types meets the schema and feeds the replica`() {
        val grant =
            AuthorityChangedPayload(
                changeKind = AuthorityChangedPayload.ChangeKind.GRANT,
                principalRef = "P-1",
                authorityVersion = 4,
                grantRecord =
                    AuthorityChangedPayloadGrantRecord(
                        "E-001",
                        listOf(
                            AuthorityChangedPayloadGrantRecordGrantsInner(
                                action = "document.read",
                                scopeLevel = AuthorityChangedPayloadGrantRecordGrantsInner.ScopeLevel.ENTITY,
                                scopeRef = "E-001",
                                validFrom = OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                            ),
                        ),
                    ),
            )
        val consent =
            AuthorityChangedPayload(
                changeKind = AuthorityChangedPayload.ChangeKind.CONSENT,
                principalRef = "agent-1",
                authorityVersion = 5,
                consentRecord =
                    AuthorityChangedPayloadConsentRecord(
                        clientEntityRef = "E-001",
                        state = AuthorityChangedPayloadConsentRecord.State.LIVE,
                        scopeItems = AuthorityChangedPayloadConsentRecord.ScopeItems.entries.toSet(),
                        validFrom = OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                    ),
            )
        val schema = schema("access", "authority-changed-payload.schema.json")
        val handler = AuthorityFeedHandler(AuthorityReplica(CLOCK))
        listOf(grant, consent).forEach { payload ->
            val json = tree(payload)
            conforms(json, schema)
            handler
                .decode(
                    envelope(ProductEventTypes.AUTHORITY_CHANGED, json.toString()),
                ).record
                .shouldBeInstanceOf<AuthorityRecord>()
        }
    }

    @Test
    fun `PolicyChanged built from its generated type is read by service-runtime's reader into the policy replica`() {
        val payload =
            PolicyChangedPayload(
                scope = "ENTITY:E-001",
                vpId = "VP-13",
                effectiveDate = LocalDate.parse("2026-10-01"),
                source = PolicyChangedPayload.Source.OVERRIDE,
                `value` = listOf(mapOf("key" to "STRICT")),
            )
        val data = tree(payload)
        conforms(data, schema("service-runtime", "policy-changed-payload.schema.json"))
        val record =
            LogRecord(
                1,
                EnvelopeJson().write(envelope(PlatformEventTypes.POLICY_CHANGED, data.toString(), TENANT)),
            )
        val replica = PolicyReplica(CLOCK)
        PolicyFeed(replica).accept(PlatformEventTypes.POLICY_CHANGED_TOPIC, record)
        replica.valueAt(PolicyScope.entity(TENANT, "E-001"), "VP-13", LocalDate.parse("2026-10-12"))?.value shouldBe
            """[{"key":"STRICT"}]"""
    }
}
