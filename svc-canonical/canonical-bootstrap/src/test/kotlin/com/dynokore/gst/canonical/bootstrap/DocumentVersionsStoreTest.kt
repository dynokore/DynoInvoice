package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentVersions
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * The document version store against a real database (P109): JooqDocumentVersions over documents written by
 * JooqDocumentStore in the full TPL-18 context, as the `canonical_runtime` login bound to a tenant (INV-AC-21469).
 */
class DocumentVersionsStoreTest {
    private val actor = SignedBindings.ACTOR
    private val on = LocalDate.of(2026, 3, 20)

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val documents = JooqDocumentStore(dsl)
        val versions = JooqDocumentVersions(dsl)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        CanonicalContext.online(dir).run { block(Boundary(it)) }
    }

    private fun newVersion() =
        NewVersion(
            """{"documentNumber":"n","lines":{"value":[],"classification":"content"},""" +
                """"taxSubtotals":{"value":[],"classification":"content"},""" +
                """"parties":{"value":{"supplier":{},"buyer":{}},"classification":"content"}}""",
            ScopeVerdict.IN_SCOPE,
            "scope-1",
            "map-1",
            null,
            SpecificationVersion.PINT_SG,
            on,
            "svc",
        )

    private fun newDocument(
        tenant: UUID,
        doc: UUID,
    ) = NewDocument(
        doc,
        tenant,
        UUID.randomUUID(),
        null,
        Direction.SALE,
        380,
        "N-$doc",
        "issuer",
        2026,
        null,
        DocumentState.READY,
        null,
        newVersion(),
    )

    private fun <T> Boundary.asTenant(
        tenant: UUID,
        block: Boundary.() -> T,
    ): T = scope.bind(TenantId(tenant), actor) { runner.inTransaction { block() } }

    /** Insert a document of the tenant and move it to [version] through createVersion. */
    private fun Boundary.seed(
        tenant: UUID,
        doc: UUID,
        version: Int,
    ) = asTenant(tenant) {
        documents.insert(newDocument(tenant, doc))
        (1 until version).forEach { documents.createVersion(doc, it, newVersion()) }
    }

    private fun Boundary.next(
        tenant: UUID,
        doc: UUID,
    ): Int = asTenant(tenant) { versions.nextVersion(doc) }

    private fun counts(tenants: List<UUID>): List<Long> =
        tenants.flatMap { tenant ->
            CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
                connection.autoCommit = false
                SignedBindings.bind(connection, tenant)
                try {
                    listOf("document", "document_version").map { table ->
                        connection.createStatement().use { st ->
                            st.executeQuery("select count(*) from canonical.$table").use { rows ->
                                rows.next()
                                rows.getLong(1)
                            }
                        }
                    }
                } finally {
                    connection.rollback()
                }
            }
        }

    @Test
    fun `INV-AC-21469 the next number is current_version plus 1, 1 for no visible row, and another tenant is invisible`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val d1 = UUID.randomUUID()
        val d2 = UUID.randomUUID()
        val d3 = UUID.randomUUID()
        b.seed(t1, d1, 1)
        b.seed(t1, d2, 3)
        b.seed(t2, d3, 2)

        b.next(t1, d1) shouldBe 2
        b.next(t1, d2) shouldBe 4
        b.next(t1, UUID.randomUUID()) shouldBe 1
        b.next(t1, d3) shouldBe 1
        // the owner of D3 sees its own number
        b.next(t2, d3) shouldBe 3
    }

    @Test
    fun `INV-AC-21469 after a createVersion of D1 from version 1 the next number is 3`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val d1 = UUID.randomUUID()
        b.seed(t1, d1, 1)
        b.next(t1, d1) shouldBe 2

        b.asTenant(t1) { documents.createVersion(d1, 1, newVersion()) }

        b.next(t1, d1) shouldBe 3
    }

    @Test
    fun `INV-AC-21469 the store never writes a row, the counts of document and document_version are equal`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val d1 = UUID.randomUUID()
        val d3 = UUID.randomUUID()
        b.seed(t1, d1, 2)
        b.seed(t2, d3, 2)
        val before = counts(listOf(t1, t2))
        before shouldBe listOf(1L, 2L, 1L, 2L)

        b.next(t1, d1)
        b.next(t1, UUID.randomUUID())
        b.next(t1, d3)

        counts(listOf(t1, t2)) shouldBe before
    }
}
