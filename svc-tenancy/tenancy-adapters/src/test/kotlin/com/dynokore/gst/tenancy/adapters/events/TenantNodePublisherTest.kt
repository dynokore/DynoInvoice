package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/** INV-AC-16723 (h): the TENANT publisher routes by node type and builds the payload with the one existing builder. */
class TenantNodePublisherTest {
    private val mapper = JsonMapper.builder().build()
    private val appended = RecordingOutbox()
    private val delegate = OrganisationStructureChangedOutbox(appended, mapper)
    private val calls = ArrayList<Pair<String, List<Any?>>>()
    private val dsl =
        DSL.using(
            MockConnection { ctx ->
                calls += ctx.sql() to ctx.bindings().toList()
                arrayOf(MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("x"))))
            },
            SQLDialect.POSTGRES,
        )
    private val writer =
        JdbcOutboxWriter(dsl, "tenancy", "tenancy-svc", "instance-1", TenancyEventCatalogue.load(), Clock.systemUTC())
    private val publisher = TenantNodePublisher(writer, delegate, dsl, mapper)

    private fun change(type: NodeType) =
        StructureChange(
            UUID.fromString("0a000000-0000-4000-8000-00000000000a"),
            "T-1",
            type,
            "T-1",
            ChangeType.CREATED,
            null,
            null,
            StructureNodeState(
                type,
                null,
                false,
                "Name",
                null,
                4,
                environment = if (type == NodeType.TENANT) Environment.PRODUCTION else null,
            ),
        )

    @Test
    fun `INV-AC-16723 a TENANT change goes to the function and its payload equals the one builder`() {
        val c = change(NodeType.TENANT)
        publisher.publish(c)
        appended.events.shouldBeEmpty()
        calls.size shouldBe 1
        calls.single().first.contains("publish_tenant_node") shouldBe true
        val row =
            mapper.readTree(
                calls
                    .single()
                    .second
                    .single()
                    .toString(),
            )
        row.get("data") shouldBe mapper.readTree(delegate.payload(c).toString())
        row.get("type").asString() shouldBe "com.dynokore.gst.tenancy.OrganisationStructureChanged.v1"
        row.get("topic").asString() shouldBe "gst.tenancy.organisation-structure-changed.v1"
        row.get("tenant_id").asString() shouldBe c.tenant.toString()
        row.get("entity_ref").asString() shouldBe "T-1"
    }

    @Test
    fun `INV-AC-16723 GROUP, ENTITY and BUSINESS_UNIT changes go to the delegate`() {
        val kinds = listOf(NodeType.GROUP, NodeType.ENTITY, NodeType.BUSINESS_UNIT)
        kinds.forEach { publisher.publish(change(it)) }
        calls.shouldBeEmpty()
        appended.events.size shouldBe kinds.size
        appended.events.map { it.type }.toSet() shouldBe
            setOf("com.dynokore.gst.tenancy.OrganisationStructureChanged.v1")
    }
}
