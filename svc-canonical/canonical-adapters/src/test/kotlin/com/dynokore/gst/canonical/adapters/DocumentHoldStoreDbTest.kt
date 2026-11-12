package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentHoldStore
import com.dynokore.gst.canonical.application.document.DocumentStates
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.time.OffsetDateTime
import java.util.UUID

/** The hold columns of `canonical.document` through the jOOQ store, over the runtime login bound to one tenant. */
class DocumentHoldStoreDbTest {
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
    private val store = JooqDocumentHoldStore(dsl, tenants)
    private val tenant = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun <T> asTenant(
        id: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(id), SignedBindings.ACTOR) { transaction.inTransaction(block) }

    @Suppress("LongParameterList") // a fixture row: one argument per stored column
    private fun insert(
        tenantId: UUID,
        entityRef: String? = "E-001",
        documentDate: String? = "2026-03-20",
        period: String? = "2026-Q1",
        posting: String? = null,
        invoice: Boolean? = null,
    ): UUID {
        val doc = UUID.randomUUID()

        fun lit(value: String?) = value?.let { "'$it'" } ?: "null"
        DocumentSql.execute(
            "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, current_version, " +
                "state, entity_ref, document_date, prescribed_period_ref, posting_timestamp, " +
                "invoice_issued_or_received) values ('$doc', '$tenantId', '${UUID.randomUUID()}', 'SALE', 380, " +
                "'N-$doc', 'issuer', 2026, '2026-03-25', '2026-03-25', 1, 'INGESTED', ${lit(entityRef)}, " +
                "${lit(documentDate)}, ${lit(period)}, ${lit(posting)}, ${invoice ?: "null"})",
        )
        return doc
    }

    @Test
    fun `INV-AC-20653 the store reads the stored facts, state and reasons of a document of the bound tenant`() {
        val doc = insert(tenant, posting = "2026-03-21T09:30:00+08:00", invoice = true)
        val before = asTenant(tenant) { store.lockForHold(doc) }!!
        before.state shouldBe DocumentStates.INGESTED
        before.holdReasons shouldBe emptyList()
        before.entityRef shouldBe "E-001"
        before.direction shouldBe Direction.SALE
        before.documentDate.toString() shouldBe "2026-03-20"
        before.prescribedPeriodRef shouldBe "2026-Q1"
        before.postingTimestamp!!.toInstant() shouldBe OffsetDateTime.parse("2026-03-21T09:30:00+08:00").toInstant()
        before.invoiceIssuedOrReceived shouldBe true

        asTenant(tenant) { store.markHeld(doc, listOf("ING-016", "PER-009")) }

        val after = asTenant(tenant) { store.lockForHold(doc) }!!
        after.state shouldBe DocumentStates.HELD
        after.holdReasons shouldBe listOf("ING-016", "PER-009")
        DocumentSql.documentColumn("state", doc) shouldBe "HELD"
        DocumentSql.documentColumn("hold_reasons", doc) shouldBe "{ING-016,PER-009}"
    }

    @Test
    fun `INV-AC-20653 a document of another tenant is never read and never changed`() {
        val mine = insert(tenant)
        val theirs = insert(other)
        asTenant(tenant) { store.lockForHold(theirs) } shouldBe null
        asTenant(tenant) { store.markHeld(theirs, listOf("ING-016")) }
        DocumentSql.documentColumn("state", theirs) shouldBe "INGESTED"
        DocumentSql.documentColumn("hold_reasons", theirs) shouldBe null
        asTenant(other) { store.lockForHold(mine) } shouldBe null
    }

    @Test
    fun `INV-AC-20653 a null entity_ref, document_date or prescribed_period_ref refuses the read naming the column`() {
        val noEntity = insert(tenant, entityRef = null)
        val noDate = insert(tenant, documentDate = null)
        val noPeriod = insert(tenant, period = null)
        shouldThrow<IllegalStateException> { asTenant(tenant) { store.lockForHold(noEntity) } }
            .message!! shouldContain "entity_ref"
        shouldThrow<IllegalStateException> { asTenant(tenant) { store.lockForHold(noDate) } }
            .message!! shouldContain "document_date"
        shouldThrow<IllegalStateException> { asTenant(tenant) { store.lockForHold(noPeriod) } }
            .message!! shouldContain "prescribed_period_ref"
    }

    @Test
    fun `INV-AC-21358 the hold read returns the stored party reference or null and nothing else changes`() {
        val withRef = insert(tenant, posting = "2026-03-21T09:30:00+08:00", invoice = true)
        val without = insert(tenant)
        DocumentSql.execute(
            "insert into canonical.document_party_ref (tenant_id, doc_uuid, party_ref) " +
                "values ('$tenant', '$withRef', 'CTP-118')",
        )
        val held = asTenant(tenant) { store.lockForHold(withRef) }!!
        held.partyRef shouldBe "CTP-118"
        held.entityRef shouldBe "E-001"
        held.state shouldBe DocumentStates.INGESTED
        held.invoiceIssuedOrReceived shouldBe true
        asTenant(tenant) { store.lockForHold(without) }!!.partyRef shouldBe null
        asTenant(other) { store.lockForHold(withRef) } shouldBe null
    }
}
