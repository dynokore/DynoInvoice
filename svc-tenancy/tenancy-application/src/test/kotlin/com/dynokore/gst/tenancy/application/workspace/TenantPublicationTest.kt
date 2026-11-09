package com.dynokore.gst.tenancy.application.workspace

import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.gst.tenancy.application.structure.tenantNode
import com.dynokore.gst.tenancy.application.tenant.CreateTenant
import com.dynokore.gst.tenancy.application.tenant.OpenTenant
import com.dynokore.gst.tenancy.application.tenant.TenantCreation
import com.dynokore.gst.tenancy.application.tenant.TenantOpening
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.application.tenant.WorkspaceArchival
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.gst.tenancy.domain.TenantState
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Records every published change, in order. */
class RecordingPublisher : StructureChangePublisher {
    val changes = mutableListOf<StructureChange>()

    override fun publish(change: StructureChange) {
        changes += change
    }
}

/** INV-AC-15381, application half: what createTenant, createWorkspace and archiveWorkspace publish. */
class TenantPublicationTest {
    private val production = "acme"
    private val productionId = TenantRefs.idOf(production)
    private val context = InMemoryTenantContext(productionId)
    private val store =
        InMemoryWorkspaceStore(context, TenantRecord(productionId.value, production, "Acme", "plan", "c-1"))
    private val published = RecordingPublisher()
    private val create = CreateWorkspace(context, DirectTransactions, store, published)
    private val archive = ArchiveWorkspace(DirectTransactions, store, published)

    private object Platform : PlatformTenantBinding {
        override fun <T> bindPlatform(
            actor: String,
            block: () -> T,
        ): T = block()
    }

    private val tenantStore =
        object : TenantStore {
            val rows = mutableListOf<TenantRecord>()

            override fun create(tenant: TenantRecord): TenantCreation {
                val same = rows.firstOrNull { it.ref == tenant.ref }
                val other = rows.any { it.contractReference == tenant.contractReference }
                return when {
                    same != null -> TenantCreation.Repeated(same)
                    other -> TenantCreation.Conflict
                    else -> TenantCreation.Created(tenant).also { rows += tenant }
                }
            }

            override fun current(): TenantRecord? = null

            override fun open(ref: String): TenantOpening = error("not used")

            override fun close() = error("not used")
        }

    private fun opening(vararg states: String): Pair<InMemoryWorkspaceStore, OpenTenant> {
        val rows =
            states.mapIndexed { i, s ->
                TenantRecord(TenantRefs.idOf("t$i").value, "t$i", "L", "p", "c-$i", state = s)
            }
        val ports = InMemoryWorkspaceStore(context, *rows.toTypedArray())
        return ports to OpenTenant(Platform, DirectTransactions, ports, "tenancy-svc")
    }

    @Test
    fun `INV-AC-19391 OpenTenant answers Opened and stores OPEN for a SCHEDULED tenant`() {
        val (ports, open) = opening("SCHEDULED")
        val answer = open.handle("t0")
        assertTrue(answer is TenantOpening.Opened && answer.tenant.state == "OPEN", answer.toString())
        assertEquals("OPEN", ports.rows[0].state)
    }

    @Test
    fun `INV-AC-19391 OpenTenant answers Repeated for an OPEN tenant and writes nothing`() {
        val (ports, open) = opening("OPEN")
        val before = ports.rows.toList()
        assertTrue(open.handle("t0") is TenantOpening.Repeated)
        assertEquals(before, ports.rows)
    }

    @Test
    fun `INV-AC-19391 OpenTenant refuses a CLOSED tenant and any other stored text and leaves the state`() {
        for (text in listOf("CLOSED", "OPENED", "open", "")) {
            val (ports, open) = opening(text)
            assertTrue(open.handle("t0") is TenantOpening.Refused, text)
            assertEquals(text, ports.rows[0].state)
        }
    }

    @Test
    fun `INV-AC-19391 OpenTenant answers Missing for an unknown ref`() {
        val (ports, open) = opening("SCHEDULED")
        assertEquals(TenantOpening.Missing, open.handle("nope"))
        assertEquals("SCHEDULED", ports.rows[0].state)
    }

    @Test
    fun `INV-AC-15381 createTenant publishes one CREATED TENANT event and a repeat or conflict none`() {
        val sink = RecordingPublisher()
        val useCase = CreateTenant(Platform, DirectTransactions, tenantStore, "tenancy-svc", sink)
        val row = TenantRecord(TenantRefs.idOf("Acme").value, "Acme", "Acme Pte Ltd", "plan", "c-A")
        assertTrue(useCase.handle(row) is TenantCreation.Created)
        val change = sink.changes.single()
        assertEquals(row.tenantId, change.tenant)
        assertEquals(NodeType.TENANT, change.nodeType)
        assertEquals("Acme", change.nodeRef)
        assertEquals("Acme", change.entityRef)
        assertEquals(ChangeType.CREATED, change.changeType)
        assertNull(change.parentRef)
        assertNull(change.previousParentRef)
        assertEquals(tenantNode(row), change.node)
        assertEquals(Environment.PRODUCTION, change.node.environment)
        assertTrue(useCase.handle(row) is TenantCreation.Repeated)
        assertEquals(TenantCreation.Conflict, useCase.handle(row.copy(ref = "Other")))
        assertEquals(1, sink.changes.size)
    }

    @Test
    fun `INV-AC-15381 createWorkspace publishes the sibling's own SANDBOX node, not the caller's, once`() {
        val done = create.handle(production, "uat") as WorkspaceCreation.Done
        val change = published.changes.single()
        assertEquals(done.workspace.tenantId, change.tenant)
        assertTrue(change.tenant != productionId.value)
        assertEquals(ChangeType.CREATED, change.changeType)
        assertEquals(done.workspace.ref, change.nodeRef)
        assertEquals(Environment.SANDBOX, change.node.environment)
        assertEquals(tenantNode(done.workspace), change.node)
        create.handle(production, "uat")
        assertEquals(1, published.changes.size)
        create.handle(production, "Uat-two")
        assertEquals(2, published.changes.size)
    }

    @Test
    fun `INV-AC-15381 archiveWorkspace publishes RETIRED once with the new version and still SANDBOX`() {
        val created = (create.handle(production, "uat") as WorkspaceCreation.Done).workspace
        assertEquals(WorkspaceArchival.ARCHIVED, archive.handle(created.ref))
        val change = published.changes.last()
        assertEquals(2, published.changes.size)
        assertEquals(ChangeType.RETIRED, change.changeType)
        assertEquals(created.tenantId, change.tenant)
        assertTrue(change.node.retired)
        assertTrue(change.node.structureVersion > created.structureVersion)
        assertEquals(Environment.SANDBOX, change.node.environment)
        assertEquals(WorkspaceArchival.ALREADY_ARCHIVED, archive.handle(created.ref))
        assertEquals(WorkspaceArchival.MISSING, archive.handle("nothing"))
        assertEquals(2, published.changes.size)
    }

    @Test
    fun `INV-AC-15381 a publisher that throws propagates so the transaction can roll back`() {
        val boom =
            object : StructureChangePublisher {
                override fun publish(change: StructureChange) = error("outbox refused")
            }
        assertThrows(IllegalStateException::class.java) {
            CreateWorkspace(context, DirectTransactions, store, boom).handle(production, "uat")
        }
    }
}
