package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentHoldStore
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentReleaseStore
import com.dynokore.gst.canonical.application.document.DocumentStates
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID

/** The release columns of `canonical.document` through the jOOQ store, over the runtime login bound to one tenant. */
class DocumentReleaseStoreDbTest {
    private val tenants = TenantContextImpl()
    private val transaction =
        TransactionRunnerImpl(
            PGSimpleDataSource().apply {
                setUrl(CanonicalDb.jdbcUrl)
                user = CanonicalDb.RUNTIME
                password = CanonicalDb.RUNTIME_PASSWORD
            },
            tenants,
            null,
            object : BindingKeySource {
                override fun currentKey(): ByteArray = CanonicalDb.bindingKey.copyOf()

                override fun previousKey(): ByteArray? = null
            },
        )
    private val dsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(mapOf(DSL.name("canonical", "document") to TableClass.TENANT)),
                tenants,
                transaction,
            ).dsl()
    private val store = JooqDocumentReleaseStore(JooqDocumentHoldStore(dsl, tenants), dsl, tenants)
    private val tenant = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun <T> asTenant(
        id: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(id), SignedBindings.ACTOR) { transaction.inTransaction(block) }

    private fun insert(
        tenantId: UUID,
        state: String,
        reasons: String?,
    ): UUID {
        val doc = UUID.randomUUID()
        DocumentSql.execute(
            "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, current_version, " +
                "state, entity_ref, document_date, prescribed_period_ref, hold_reasons) values ('$doc', " +
                "'$tenantId', '${UUID.randomUUID()}', 'SALE', 380, 'N-$doc', 'issuer', 2026, '2026-03-25', " +
                "'2026-03-25', 1, '$state', 'E-001', '2026-03-20', '2026-Q1', ${reasons?.let { "'$it'" } ?: "null"})",
        )
        return doc
    }

    @Test
    fun `INV-AC-21248 markReleased sets INGESTED and clears the reasons of a HELD document, and a repeat fails`() {
        val doc = insert(tenant, "HELD", "{MDM-001}")
        asTenant(tenant) { store.lockForRelease(doc)!!.holdReasons } shouldBe listOf("MDM-001")
        asTenant(tenant) { store.markReleased(doc) }
        DocumentSql.documentColumn("state", doc) shouldBe DocumentStates.INGESTED
        DocumentSql.documentColumn("hold_reasons", doc) shouldBe null
        shouldThrow<IllegalStateException> { asTenant(tenant) { store.markReleased(doc) } }
    }

    @Test
    fun `INV-AC-21248 a document of another tenant is never read nor released`() {
        val theirs = insert(other, "HELD", "{MDM-001}")
        asTenant(tenant) { store.lockForRelease(theirs) } shouldBe null
        shouldThrow<IllegalStateException> { asTenant(tenant) { store.markReleased(theirs) } }
        DocumentSql.documentColumn("state", theirs) shouldBe "HELD"
        DocumentSql.documentColumn("hold_reasons", theirs) shouldBe "{MDM-001}"
    }

    @Test
    fun `INV-AC-21248 markReleased changes no row of a document that is not HELD`() {
        val doc = insert(tenant, "INGESTED", null)
        shouldThrow<IllegalStateException> { asTenant(tenant) { store.markReleased(doc) } }
        DocumentSql.documentColumn("state", doc) shouldBe "INGESTED"
    }
}
