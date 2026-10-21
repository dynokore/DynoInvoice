package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqSourceEventStore
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SOURCE_EVENT
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

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

    /** After `boundDocUuid` finds no value, waits at a barrier of two parties for at most 2 seconds, then goes on. */
    private class BarrierAfterNoBinding(
        private val inner: SourceEventStore,
        private val barrier: CyclicBarrier,
        private val timeouts: AtomicInteger,
    ) : SourceEventStore by inner {
        override fun boundDocUuid(key: SourceKey): UUID? {
            val bound = inner.boundDocUuid(key)
            if (bound == null) {
                try {
                    barrier.await(2, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    timeouts.incrementAndGet()
                }
            }
            return bound
        }
    }

    /** Runs the two calls at the same time, each bound to its own tenant on its own connection. */
    private fun concurrently(
        first: Pair<UUID, SourceKey>,
        second: Pair<UUID, SourceKey>,
    ): Pair<List<UUID>, Int> {
        val timeouts = AtomicInteger()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(first, second).map { (boundTenant, key) ->
                    pool.submit<UUID> {
                        tenants.bind(TenantId(boundTenant), SignedBindings.ACTOR) {
                            recorder(BarrierAfterNoBinding(store, barrier, timeouts)).record(key).docUuid
                        }
                    }
                }
            return futures.map { it.get(30, TimeUnit.SECONDS) } to timeouts.get()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun rowsOf(
        owner: UUID,
        sourceKey: String,
    ): List<Pair<Long, UUID>> =
        CanonicalDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select source_revision, doc_uuid from canonical.source_event " +
                        "where tenant_id = ? and source_key = ? order by 1",
                ).use { statement ->
                    statement.setObject(1, owner)
                    statement.setString(2, sourceKey)
                    statement.executeQuery().use { rows ->
                        generateSequence {
                            if (rows.next()) rows.getLong(1) to rows.getObject(2, UUID::class.java) else null
                        }.toList()
                    }
                }
        }

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

    @Test
    fun `INV-AC-7864 concurrent first records of one source key with two revisions bind one Doc UUID`() {
        val (docUuids, _) = concurrently(tenant to key("k-race", 1), tenant to key("k-race", 2))
        docUuids[0] shouldBe docUuids[1]
        rowsOf(tenant, "k-race") shouldBe listOf(1L to docUuids[0], 2L to docUuids[0])
    }

    @Test
    fun `INV-AC-7864 the same source key in tenant A and tenant B is not serialised and the Doc UUIDs differ`() {
        val tenantB = UUID.randomUUID()
        val (docUuids, timeouts) =
            concurrently(tenant to key("k-shared", 1), tenantB to SourceKey(tenantB, "erp", "k-shared", 1))
        timeouts shouldBe 0 // both calls reached the barrier together
        docUuids[0] shouldNotBe docUuids[1]
        rowsOf(tenant, "k-shared").map { it.second } shouldBe listOf(docUuids[0])
        rowsOf(tenantB, "k-shared").map { it.second } shouldBe listOf(docUuids[1])
        // a read as tenant A through the runtime login returns none of the rows of tenant B
        asTenant {
            transaction.inTransaction {
                dsl.selectFrom(SOURCE_EVENT).where(SOURCE_EVENT.TENANT_ID.eq(tenant)).fetch(SOURCE_EVENT.DOC_UUID)
            }
        } shouldBe listOf(docUuids[0])
    }

    @Test
    fun `INV-AC-7864 the lock is released by the rollback so a second call for the same key succeeds`() {
        shouldThrow<IllegalStateException> { asTenant { recorder(FailAfterInsert(store)).record(key("k-unlock", 1)) } }
        asTenant { recorder().record(key("k-unlock", 1)) }.inserted shouldBe true
    }

    private companion object {
        const val COUNT = "select count(*) from canonical.source_event where tenant_id = ?"
    }
}
