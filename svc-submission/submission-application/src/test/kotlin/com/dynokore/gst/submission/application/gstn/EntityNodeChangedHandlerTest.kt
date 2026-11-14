package com.dynokore.gst.submission.application.gstn

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The structure-node consumer and the GSTN lookup over an in-memory port (INV-AC-22282). */
class EntityNodeChangedHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000022"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000023"))

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class Memory : EntityNodes {
        val rows = mutableMapOf<Pair<TenantId, String>, EntityNode>()

        override fun record(
            tenant: TenantId,
            node: EntityNode,
        ): Boolean {
            val key = tenant to node.nodeRef
            val old = rows[key]
            if (old != null && node.structureVersion <= old.structureVersion) return false
            rows[key] = node
            return true
        }

        override fun find(
            tenant: TenantId,
            nodeRef: String,
        ): EntityNode? = rows[tenant to nodeRef]
    }

    private val store = Memory()
    private val lookup = StoredEntityGstns(store)
    private var next: () -> EntityNodeChangedData = { error("unset") }
    private val handler = EntityNodeChangedHandler(tenants, { next() }, store)

    private fun deliver(
        ref: String = "E",
        kind: String = "ENTITY",
        parent: String? = null,
        gstn: String? = null,
        version: Long? = 1,
    ): HandleOutcome {
        next = { EntityNodeChangedData(ref, kind, parent, gstn, version) }
        return handler.handle(envelope())
    }

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/tenancy-svc",
            type = "com.dynokore.gst.tenancy.OrganisationStructureChanged.v1",
            subject = "E",
            time = Instant.parse("2026-10-19T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-22282 a first ENTITY node is stored`() {
        deliver(gstn = "G1", parent = "P") shouldBe HandleOutcome.APPLIED
        store.find(tenant, "E") shouldBe EntityNode("E", "ENTITY", "P", "G1", 1)
    }

    @Test
    fun `INV-AC-22282 the same structure version again is ignored`() {
        deliver(gstn = "G1", version = 2) shouldBe HandleOutcome.APPLIED
        deliver(gstn = "G2", version = 2) shouldBe HandleOutcome.IGNORED
        store.find(tenant, "E")!!.gstn shouldBe "G1"
    }

    @Test
    fun `INV-AC-22282 a lower version is ignored`() {
        deliver(gstn = "G1", version = 3) shouldBe HandleOutcome.APPLIED
        deliver(gstn = "G0", version = 2) shouldBe HandleOutcome.IGNORED
        store.find(tenant, "E") shouldBe EntityNode("E", "ENTITY", null, "G1", 3)
    }

    @Test
    fun `INV-AC-22282 a higher version with another GSTN replaces the node`() {
        deliver(gstn = "G1", version = 1) shouldBe HandleOutcome.APPLIED
        deliver(gstn = "G2", version = 2) shouldBe HandleOutcome.APPLIED
        store.find(tenant, "E") shouldBe EntityNode("E", "ENTITY", null, "G2", 2)
    }

    @Test
    fun `INV-AC-22282 TENANT, BUSINESS_UNIT and a missing node store nothing`() {
        deliver(ref = "T", kind = "TENANT") shouldBe HandleOutcome.IGNORED
        deliver(ref = "B", kind = "BUSINESS_UNIT") shouldBe HandleOutcome.IGNORED
        deliver(ref = "N", version = null) shouldBe HandleOutcome.IGNORED
        store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-22282 the lookup resolves own GSTN, else the parent group, else null`() {
        deliver(ref = "G", kind = "GROUP", gstn = "GG") shouldBe HandleOutcome.APPLIED
        deliver(ref = "P", kind = "ENTITY", gstn = "PP") shouldBe HandleOutcome.APPLIED
        deliver(ref = "own", gstn = "OWN", parent = "G")
        deliver(ref = "viaGroup", parent = "G")
        deliver(ref = "viaEntity", parent = "P")
        deliver(ref = "none", parent = "none-parent")
        deliver(ref = "GN", kind = "GROUP", gstn = null)
        deliver(ref = "underEmptyGroup", parent = "GN")
        lookup.gstnOf(tenant, "own") shouldBe "OWN"
        lookup.gstnOf(tenant, "viaGroup") shouldBe "GG"
        lookup.gstnOf(tenant, "viaEntity") shouldBe null
        lookup.gstnOf(tenant, "none") shouldBe null
        lookup.gstnOf(tenant, "underEmptyGroup") shouldBe null
        lookup.gstnOf(tenant, "unknown") shouldBe null
        lookup.gstnOf(tenant, "G") shouldBe null
    }

    @Test
    fun `INV-AC-22282 a blank own GSTN is never returned`() {
        // The node type refuses a blank GSTN, so a blank one is a ContractViolation-class input, not a value.
        shouldThrow<IllegalArgumentException> { EntityNode("E", "ENTITY", "P", " ", 1) }
        val noParentGstn = EntityNode("E", "ENTITY", "P", null, 1)
        store.record(tenant, noParentGstn)
        store.record(tenant, EntityNode("P", "GROUP", null, null, 1))
        lookup.gstnOf(tenant, "E") shouldBe null
    }

    @Test
    fun `INV-AC-22282 a node of tenant A is not found under tenant B`() {
        deliver(gstn = "G1") shouldBe HandleOutcome.APPLIED
        store.find(other, "E") shouldBe null
        lookup.gstnOf(other, "E") shouldBe null
        lookup.gstnOf(tenant, "E") shouldBe "G1"
    }

    @Test
    fun `INV-AC-22282 a ContractViolation from the reader propagates and stores nothing`() {
        val h = EntityNodeChangedHandler(tenants, { throw ContractViolation("member is missing") }, store)
        shouldThrow<ContractViolation> { h.handle(envelope()) }
        store.rows.isEmpty() shouldBe true
    }
}
