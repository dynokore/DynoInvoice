package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityConstitutions
import com.dynokore.gst.canonical.application.entity.EntityConstitutionFact
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.util.UUID

/**
 * The local entity constitution table against a real database (P109): the jOOQ adapter in the full TPL-18 context, as
 * the `canonical_runtime` login bound to a tenant. The newest event version wins, the tenant is isolated by forced
 * row-level security, and the runtime role cannot delete (INV-REQ-479, INV-Q-21383 A).
 */
class EntityConstitutionStoreTest {
    private val actor = SignedBindings.ACTOR

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val store = JooqEntityConstitutions(context.getBean(DSLContext::class.java))
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        CanonicalContext.online(dir).run { block(Boundary(it)) }
    }

    private fun fact(
        tenant: UUID,
        constitution: String,
        version: Long,
        entity: String = "ENT-1",
    ) = EntityConstitutionFact(tenant, entity, constitution, version)

    private fun Boundary.applyAs(fact: EntityConstitutionFact) =
        scope.bind(TenantId(fact.tenantId), actor) { store.apply(fact) }

    private fun Boundary.constitutionAs(
        session: UUID,
        tenant: UUID,
        entity: String,
    ): String? = scope.bind(TenantId(session), actor) { store.constitutionOf(tenant, entity) }

    /** Every row of the two tenants, read by the owner (no row security) so a stray row would show. */
    private fun rowsOf(vararg tenants: UUID): List<List<String?>> =
        CanonicalDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select tenant_id::text, entity_ref, constitution, version::text from canonical.entity_constitution " +
                        "where tenant_id = any (?::uuid[]) order by tenant_id::text, entity_ref",
                ).use { statement ->
                    statement.setArray(1, connection.createArrayOf("uuid", tenants.map { it.toString() }.toTypedArray()))
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add((1..4).map { rows.getString(it) }) }
                    }
                }
        }

    private fun runtimeRefusal(
        tenant: UUID,
        statement: String,
    ): String? =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                connection.prepareStatement(statement).use { it.executeUpdate() }
                null
            } catch (refused: java.sql.SQLException) {
                refused.sqlState
            } finally {
                connection.rollback()
            }
        }

    @Test
    fun `INV-AC-21397 the newest version per tenant and entity is kept and answered`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        listOf(
            fact(t1, "PRIVATE_LIMITED", 5),
            fact(t1, "PARTNERSHIP", 4),
            fact(t1, "SOLE_PROPRIETOR", 5),
            fact(t1, "PARTNERSHIP", 6),
            fact(t2, "PUBLIC_LIMITED", 1),
        ).forEach { b.applyAs(it) }

        rowsOf(t1, t2) shouldBe
            listOf(
                listOf(t1.toString(), "ENT-1", "PARTNERSHIP", "6"),
                listOf(t2.toString(), "ENT-1", "PUBLIC_LIMITED", "1"),
            ).sortedBy { it[0] }
        b.constitutionAs(t1, t1, "ENT-1") shouldBe "PARTNERSHIP"
        b.constitutionAs(t1, t1, "ENT-2") shouldBe null
    }

    @Test
    fun `INV-AC-21397 a session bound to another tenant reads nothing of the tenant (row-level security)`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        b.applyAs(fact(t1, "PARTNERSHIP", 6))
        b.applyAs(fact(t2, "PUBLIC_LIMITED", 1))

        b.constitutionAs(t2, t1, "ENT-1") shouldBe null
        b.constitutionAs(t2, t2, "ENT-1") shouldBe "PUBLIC_LIMITED"
        // a write for another tenant than the bound one is refused by the policy's WITH CHECK
        val refused = runCatching { b.scope.bind(TenantId(t2), actor) { b.store.apply(fact(t1, "SOLE_PROPRIETOR", 9)) } }
        (refused.isFailure) shouldBe true
        rowsOf(t1).single()[3] shouldBe "6"
    }

    @Test
    fun `INV-AC-21397 applying an older or equal version after a newer one leaves the newer row`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.applyAs(fact(t1, "PARTNERSHIP", 6))
        b.applyAs(fact(t1, "PRIVATE_LIMITED", 5))
        b.applyAs(fact(t1, "SOLE_PROPRIETOR", 6))

        rowsOf(t1) shouldBe listOf(listOf(t1.toString(), "ENT-1", "PARTNERSHIP", "6"))
        b.constitutionAs(t1, t1, "ENT-1") shouldBe "PARTNERSHIP"
    }

    @Test
    fun `INV-AC-21397 the runtime role holds no DELETE privilege on the table`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.applyAs(fact(t1, "PARTNERSHIP", 6))

        CanonicalDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select has_table_privilege('canonical_runtime', 'canonical.entity_constitution', 'DELETE')::text, " +
                        "has_table_privilege('canonical_runtime', 'canonical.entity_constitution', 'UPDATE')::text",
                ).use { s ->
                    s.executeQuery().use { r ->
                        r.next() shouldBe true
                        r.getString(1) shouldBe "false"
                        r.getString(2) shouldBe "true"
                    }
                }
        }
        runtimeRefusal(t1, "delete from canonical.entity_constitution") shouldBe "42501"
        rowsOf(t1).size shouldBe 1
    }
}
