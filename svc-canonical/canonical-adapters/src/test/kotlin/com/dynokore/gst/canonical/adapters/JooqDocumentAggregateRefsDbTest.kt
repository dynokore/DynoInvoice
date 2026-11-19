package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentAggregateRefs
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

/** The aggregate reference of `canonical.document` through the jOOQ store, over the runtime login of one tenant. */
class JooqDocumentAggregateRefsDbTest {
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
                TenancyTables.declared(
                    mapOf(
                        DSL.name("canonical", "document") to TableClass.TENANT,
                        DSL.name("canonical", "document_party_ref") to TableClass.TENANT,
                    ),
                ),
                tenants,
                transaction,
            ).dsl()
    private val refs = JooqDocumentAggregateRefs(dsl, tenants)
    private val tenant = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun <T> asTenant(
        id: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(id), SignedBindings.ACTOR) { transaction.inTransaction(block) }

    private fun insert(tenantId: UUID): UUID {
        val doc = UUID.randomUUID()
        DocumentSql.execute(
            "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, current_version, " +
                "state) values ('$doc', '$tenantId', '${UUID.randomUUID()}', 'SALE', 380, 'N-$doc', 'issuer', " +
                "2026, '2026-03-25', '2026-03-25', 1, 'INGESTED')",
        )
        return doc
    }

    @Test
    fun `INV-AC-25234 a first bind returns true and the value is stored`() {
        val doc = insert(tenant)
        asTenant(tenant) { refs.bind(doc, "AGG-2026-0001") } shouldBe true
        asTenant(tenant) { refs.aggregateIdOf(doc) } shouldBe "AGG-2026-0001"
    }

    @Test
    fun `INV-AC-25234 a second bind with another value returns false and leaves the value`() {
        val doc = insert(tenant)
        asTenant(tenant) { refs.bind(doc, "AGG-2026-0001") } shouldBe true
        asTenant(tenant) { refs.bind(doc, "AGG-2026-0002") } shouldBe false
        asTenant(tenant) { refs.aggregateIdOf(doc) } shouldBe "AGG-2026-0001"
    }

    @Test
    fun `INV-AC-25234 a bind of another tenant's document returns false and the column stays null`() {
        val theirs = insert(other)
        asTenant(tenant) { refs.bind(theirs, "AGG-2026-0001") } shouldBe false
        DocumentSql.documentColumn("aggregate_id", theirs) shouldBe null
    }

    @Test
    fun `INV-AC-25234 a bind of an unknown docUuid returns false`() {
        asTenant(tenant) { refs.bind(UUID.randomUUID(), "AGG-2026-0001") } shouldBe false
    }

    @Test
    fun `INV-AC-25234 a blank value is refused`() {
        val doc = insert(tenant)
        shouldThrow<IllegalArgumentException> { asTenant(tenant) { refs.bind(doc, " ") } }
        DocumentSql.documentColumn("aggregate_id", doc) shouldBe null
    }
}
