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
import io.kotest.matchers.shouldBe
import org.jooq.JSONB
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** INV-AC-14189 on PostgreSQL: the result of the first record is stored and replayed on a repeat of the key. */
class SourceEventReplayResultTest {
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
                override fun currentKey(): ByteArray = CanonicalDb.bindingKey.copyOf()

                override fun previousKey(): ByteArray? = null
            },
        )
    private val dsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(mapOf(DSL.name("canonical", "source_event") to TableClass.TENANT)),
                tenants,
                transaction,
            ).dsl()
    private val store = JooqSourceEventStore(dsl)

    private fun <T> asTenant(block: () -> T): T = tenants.bind(TenantId(tenant), SignedBindings.ACTOR, block)

    private fun key(
        sourceKey: String,
        revision: Long,
    ) = SourceKey(tenant, "erp", sourceKey, revision)

    private fun recorder(source: SourceEventStore = store) =
        RecordSourceEvent(source, transaction) { UUID.randomUUID() }

    private fun count(sourceKey: String): Int =
        CanonicalDb.superuser().use { connection ->
            connection
                .prepareStatement("select count(*) from canonical.source_event where tenant_id = ? and source_key = ?")
                .use { statement ->
                    statement.setObject(1, tenant)
                    statement.setString(2, sourceKey)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }

    /** jOOQ compares JSONB by parsed content, not by text. */
    private fun json(text: String) = JSONB.valueOf(text)

    @Test
    fun `INV-AC-14189 the first record stores the text onNew returned and returns it with inserted true`() {
        val calls = AtomicInteger()
        val recorded =
            asTenant {
                recorder().record(key("k-first", 1)) {
                    calls.incrementAndGet()
                    """{"docUuid":"$it","rev":1}"""
                }
            }
        recorded.inserted shouldBe true
        calls.get() shouldBe 1
        json(recorded.result) shouldBe json("""{"docUuid":"${recorded.docUuid}","rev":1}""")
        count("k-first") shouldBe 1
    }

    @Test
    fun `INV-AC-14189 a repeat returns the same JSON, inserted false, no onNew call and no new row`() {
        val first = asTenant { recorder().record(key("k-repeat", 1)) { """{"r":"R1"}""" } }
        val calls = AtomicInteger()
        val repeat =
            asTenant {
                recorder().record(key("k-repeat", 1)) {
                    calls.incrementAndGet()
                    """{"r":"other"}"""
                }
            }
        repeat.inserted shouldBe false
        repeat.docUuid shouldBe first.docUuid
        json(repeat.result) shouldBe json(first.result)
        calls.get() shouldBe 0
        count("k-repeat") shouldBe 1
    }

    @Test
    fun `INV-AC-14189 a repeat of revision 1 after revision 2 returns the revision 1 result`() {
        asTenant { recorder().record(key("k-amend", 1)) { """{"r":"R1"}""" } }
        asTenant { recorder().record(key("k-amend", 2)) { """{"r":"R2"}""" } }
        count("k-amend") shouldBe 2
        val calls = AtomicInteger()
        val repeat =
            asTenant {
                recorder().record(key("k-amend", 1)) {
                    calls.incrementAndGet()
                    """{"r":"R3"}"""
                }
            }
        repeat.inserted shouldBe false
        json(repeat.result) shouldBe json("""{"r":"R1"}""")
        calls.get() shouldBe 0
        count("k-amend") shouldBe 2
    }

    @Test
    fun `INV-AC-14189 an onNew that throws leaves no row and the next call with the same key succeeds`() {
        shouldThrow<IllegalArgumentException> {
            asTenant { recorder().record(key("k-throw", 1)) { throw IllegalArgumentException("derivation failed") } }
        }
        count("k-throw") shouldBe 0
        val again = asTenant { recorder().record(key("k-throw", 1)) { """{"r":"ok"}""" } }
        again.inserted shouldBe true
        count("k-throw") shouldBe 1
    }

    @Test
    fun `INV-AC-14189 a stored row without a result raises IllegalStateException and returns nothing`() {
        asTenant { recorder().record(key("k-null", 1)) { """{"r":"R1"}""" } }
        val noResult =
            object : SourceEventStore by store {
                override fun resultOf(key: SourceKey): String? = null
            }
        val calls = AtomicInteger()
        shouldThrow<IllegalStateException> {
            asTenant {
                recorder(noResult).record(key("k-null", 1)) {
                    calls.incrementAndGet()
                    """{"r":"x"}"""
                }
            }
        }
        calls.get() shouldBe 0
    }

    @Test
    fun `INV-AC-14189 the runtime login inserting a row without a result is refused with SQLSTATE 23514`() {
        val state =
            CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
                connection.autoCommit = false
                SignedBindings.bind(connection, tenant)
                try {
                    connection
                        .prepareStatement(
                            "insert into canonical.source_event " +
                                "(tenant_id, source_system, source_key, source_revision, doc_uuid) " +
                                "values (?, 'erp', 'k-sql', 1, ?)",
                        ).use {
                            it.setObject(1, tenant)
                            it.setObject(2, UUID.randomUUID())
                            it.executeUpdate()
                        }
                    null
                } catch (refused: SQLException) {
                    refused.sqlState
                } finally {
                    connection.rollback()
                }
            }
        state shouldBe "23514"
    }
}
