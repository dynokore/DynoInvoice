package com.dynokore.gst.tenancy.application.structure

import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.application.workspace.DirectTransactions
import com.dynokore.gst.tenancy.domain.Environment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

/** INV-Q-9032 A: the TENANT node of getEntityStructure carries the stored tenant environment, equal for equal. */
class TenantNodeEnvironmentTest {
    /** A structure store that holds no node: every lookup answers null, so only the tenant row is found. */
    private val emptyStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StructureStore::class.java)) { _, _, _ -> null }
            as StructureStore

    private fun queries(row: TenantRecord): StructureQueries {
        val tenants =
            object : TenantStore {
                override fun create(tenant: TenantRecord) = error("not used")

                override fun current() = row
            }
        return StructureQueries(
            DirectTransactions,
            emptyStore,
            tenants,
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EntityStatusStore::class.java)) { _, _, _ -> null }
                as EntityStatusStore,
            ProfileExtras { emptyList() },
        )
    }

    private fun row(
        ref: String,
        environment: Environment,
    ) = TenantRecord(UUID.randomUUID(), ref, ref, "plan", "c-$ref", environment = environment)

    @Test
    fun `a workspace tenant and its look-alike production sibling each carry their own environment`() {
        val sandbox = row("Acme-sandbox", Environment.SANDBOX)
        val production = row("Acme", Environment.PRODUCTION)
        assertEquals(Environment.SANDBOX, queries(sandbox).node("Acme-sandbox")!!.node.environment)
        assertEquals(Environment.PRODUCTION, queries(production).node("Acme")!!.node.environment)
    }

    @Test
    fun `INV-AC-9149 tenancy part the tenant node states SANDBOX for a workspace and PRODUCTION otherwise`() {
        assertEquals(Environment.SANDBOX, queries(row("W", Environment.SANDBOX)).node("W")!!.node.environment)
        assertEquals(Environment.PRODUCTION, queries(row("P", Environment.PRODUCTION)).node("P")!!.node.environment)
    }

    @Test
    fun `an ENTITY node of a SANDBOX tenant has no environment`() {
        entityNode(
            EntityRecord(UUID.randomUUID(), "E1", "G", "Acme Pte Ltd", "PTE", null, null, "AB12", "ACTIVE", false, 1),
        ).environment.let(::assertNull)
    }
}
