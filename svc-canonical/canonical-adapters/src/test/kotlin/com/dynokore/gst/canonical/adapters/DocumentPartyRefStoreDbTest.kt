package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentHoldStore
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentPartyRefs
import com.dynokore.gst.canonical.application.document.RecordPartyRef
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

/** `canonical.document_party_ref` through RecordPartyRef and the hold store, over the runtime login. */
class DocumentPartyRefStoreDbTest {
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
    private val hold = JooqDocumentHoldStore(dsl, tenants)
    private val record = RecordPartyRef(JooqDocumentPartyRefs(dsl, tenants))
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()

    private fun <T> asTenant(
        id: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(id), SignedBindings.ACTOR) { transaction.inTransaction(block) }

    private fun document(tenant: UUID): UUID {
        val doc = UUID.randomUUID()
        DocumentSql.execute(
            "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, current_version, " +
                "state, entity_ref, document_date, prescribed_period_ref) values ('$doc', '$tenant', " +
                "'${UUID.randomUUID()}', 'SALE', 380, 'N-$doc', 'issuer', 2026, '2026-03-25', '2026-03-25', 1, " +
                "'INGESTED', 'E-001', '2026-03-20', '2026-Q1')",
        )
        return doc
    }

    private fun rowCount(doc: UUID) =
        DocumentSql.one("select count(*) from canonical.document_party_ref where doc_uuid = ?", doc)

    private fun read(
        tenant: UUID,
        doc: UUID,
    ) = asTenant(tenant) { hold.lockForHold(doc) }

    @Test
    fun `INV-AC-21358 the record stores one row per document, an amendment replaces it and the hold reads it`() {
        val d1 = document(t1)
        asTenant(t1) { record.record(d1, "P1") }
        rowCount(d1) shouldBe "1"
        read(t1, d1)!!.partyRef shouldBe "P1"
        asTenant(t1) { record.record(d1, "P1") }
        rowCount(d1) shouldBe "1"
        asTenant(t1) { record.record(d1, "P2") }
        rowCount(d1) shouldBe "1"
        read(t1, d1)!!.partyRef shouldBe "P2"
        DocumentSql.one("select party_ref from canonical.document_party_ref where doc_uuid = ?", d1) shouldBe "P2"
    }

    @Test
    fun `INV-AC-21358 a blank or over-long reference writes no row and a document without a row reads null`() {
        val d2 = document(t1)
        val d3 = document(t1)
        shouldThrow<IllegalArgumentException> { asTenant(t1) { record.record(d2, " ") } }
        shouldThrow<IllegalArgumentException> { asTenant(t1) { record.record(d2, "x".repeat(41)) } }
        rowCount(d2) shouldBe "0"
        rowCount(d3) shouldBe "0"
        read(t1, d2)!!.partyRef shouldBe null
        read(t1, d3)!!.partyRef shouldBe null
        read(t1, d3)!!.entityRef shouldBe "E-001"
    }

    @Test
    fun `INV-AC-21358 a row of one tenant is not readable under another and the database refuses a bad value`() {
        val d1 = document(t1)
        asTenant(t1) { record.record(d1, "P1") }
        read(t2, d1) shouldBe null
        asTenant(t2) { dsl.fetchCount(DSL.table(DSL.name("canonical", "document_party_ref"))) } shouldBe 0
        asTenant(t1) { dsl.fetchCount(DSL.table(DSL.name("canonical", "document_party_ref"))) } shouldBe 1
        DocumentSql.runtimeRefusal(
            t1,
            "insert into canonical.document_party_ref (tenant_id, doc_uuid, party_ref) " +
                "values ('$t1', '${UUID.randomUUID()}', '')",
        ) shouldBe "23514"
        DocumentSql.runtimeRefusal(
            t2,
            "insert into canonical.document_party_ref (tenant_id, doc_uuid, party_ref) " +
                "values ('$t1', '${UUID.randomUUID()}', 'P')",
        ) shouldBe "42501"
    }

    @Test
    fun `INV-AC-21358 the table has forced row level security, a policy and select, insert and update grants only`() {
        DocumentSql.rows(
            "select relrowsecurity, relforcerowsecurity from pg_class " +
                "where oid = 'canonical.document_party_ref'::regclass",
        ) shouldBe listOf(listOf("t", "t"))
        DocumentSql.rows(
            "select policyname from pg_policies where schemaname = 'canonical' and tablename = 'document_party_ref'",
        ) shouldBe listOf(listOf("document_party_ref_tenant_isolation"))
        DocumentSql
            .rows(
                "select privilege_type from information_schema.role_table_grants where table_schema = 'canonical' " +
                    "and table_name = 'document_party_ref' and grantee = 'canonical_runtime'",
            ).map { it[0] }
            .toSet() shouldBe setOf("SELECT", "INSERT", "UPDATE")
        DocumentSql.rows(
            "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid " +
                "and a.attnum = any(i.indkey) " +
                "where i.indrelid = 'canonical.document_party_ref'::regclass and i.indisprimary order by a.attname",
        ) shouldBe listOf(listOf("doc_uuid"), listOf("tenant_id"))
    }
}
