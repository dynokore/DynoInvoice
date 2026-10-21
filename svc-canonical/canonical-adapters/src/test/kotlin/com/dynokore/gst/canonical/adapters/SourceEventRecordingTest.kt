package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqSourceEventStore
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.application.source.SourceEventStore
import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID

/** INV-AC-2177 and INV-AC-7864 on PostgreSQL, through the recording function of canonical-application. */
class SourceEventRecordingTest {
    private val tenant = UUID.randomUUID()

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
                override fun currentKey(): ByteArray = CanonicalDb.bindingKey.copyOf() // the runner zeroes what it gets

                override fun previousKey(): ByteArray? = null
            },
        )
    private val tables =
        TenancyTables.declared(mapOf(DSL.name("canonical", "source_event") to TableClass.TENANT))
    private val dsl =
        TenancyJooq.configure(DefaultConfiguration().set(SQLDialect.POSTGRES), tables, tenants, transaction).dsl()

    /** Runs [block] bound to [tenant] and [SignedBindings.ACTOR] the way a request does. */
    private fun <T> asTenant(block: () -> T): T = tenants.bind(TenantId(tenant), SignedBindings.ACTOR, block)

    private class FailAfterInsert(
        private val inner: SourceEventStore,
    ) : SourceEventStore by inner {
        override fun insert(
            key: SourceKey,
            docUuid: UUID,
        ): Boolean {
            inner.insert(key, docUuid)
            error("forced failure after the insert and before commit")
        }
    }

    private val store = JooqSourceEventStore(dsl)

    private fun recorder(source: SourceEventStore = store) =
        RecordSourceEvent(source, transaction) { UUID.randomUUID() }

    private fun key(
        sourceKey: String,
        revision: Long,
    ) = SourceKey(tenant, "erp", sourceKey, revision)

    private fun rowsOfTenant(): Int =
        CanonicalDb.superuser().use { connection ->
            connection.prepareStatement(COUNT).use { statement ->
                statement.setObject(1, tenant)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun ingestion(): List<String> =
        CanonicalDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select table_schema || '.' || table_name from information_schema.tables " +
                        "where table_schema = 'ingestion' order by 1",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
                }
        }

    @Test
    fun `INV-AC-2177 recording an event writes the source_event row and its Doc UUID in one canonical transaction`() {
        val before = ingestion()
        val recorded = asTenant { recorder().record(key("k-2177", 1)) }
        recorded.inserted shouldBe true
        rowsOfTenant() shouldBe 1
        recorded.docUuid shouldNotBe null
        ingestion() shouldBe before
        ingestion().shouldBeEmpty()
    }

    @Test
    fun `INV-AC-7864 first call, repeat, higher revision and another key follow the contract rules`() {
        val before = ingestion()
        val first = asTenant { recorder().record(key("k", 1)) }
        first.inserted shouldBe true
        rowsOfTenant() shouldBe 1

        val repeat = asTenant { recorder().record(key("k", 1)) }
        repeat.inserted shouldBe false
        repeat.docUuid shouldBe first.docUuid
        rowsOfTenant() shouldBe 1

        val second = asTenant { recorder().record(key("k", 2)) }
        second.inserted shouldBe true
        second.docUuid shouldBe first.docUuid
        rowsOfTenant() shouldBe 2

        val other = asTenant { recorder().record(key("k2", 1)) }
        other.inserted shouldBe true
        other.docUuid shouldNotBe first.docUuid
        rowsOfTenant() shouldBe 3

        asTenant { recorder().record(key("k", 1)) }.docUuid shouldBe first.docUuid // never regenerated on a retry
        ingestion() shouldBe before
    }

    @Test
    fun `INV-AC-7864 a failure forced after the insert leaves neither the row nor a Doc UUID binding`() {
        val before = ingestion()
        shouldThrow<IllegalStateException> { asTenant { recorder(FailAfterInsert(store)).record(key("k-fail", 1)) } }
        rowsOfTenant() shouldBe 0
        // no binding survives: the same key now gets a fresh Doc UUID of its own and one row
        val recorded = asTenant { recorder().record(key("k-fail", 1)) }
        recorded.inserted shouldBe true
        rowsOfTenant() shouldBe 1
        ingestion() shouldBe before
    }

    private companion object {
        const val COUNT = "select count(*) from canonical.source_event where tenant_id = ?"
    }
}
