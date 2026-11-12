package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqAcknowledgementLocatorStore
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocator
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** INV-AC-20570: the table of the acknowledgement locator is unique, append-only and tenant-isolated. */
class AcknowledgementLocatorDbTest {
    private val at = Instant.parse("2026-01-15T00:00:00Z")
    private val doc = UUID.randomUUID()

    /** Runs [block] on the store of a runtime transaction bound to [tenant]; commits at the end. */
    private fun <T> withStore(
        tenant: UUID,
        block: (JooqAcknowledgementLocatorStore) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            block(
                JooqAcknowledgementLocatorStore(DSL.using(connection, SQLDialect.POSTGRES)),
            ).also { connection.commit() }
        }

    private fun refusedAs(
        login: String,
        password: String,
        tenant: UUID,
        sql: String,
    ): SQLException =
        EvidenceDb.connect(login, password).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            val failure = runCatching { connection.createStatement().use { it.execute(sql) } }.exceptionOrNull()
            connection.rollback()
            failure as SQLException
        }

    private fun insertSql(
        tenant: UUID,
        ack: String,
        event: String,
    ) = "insert into evidence.acknowledgement_locator (tenant_id, ack_id, doc_uuid, version, source_event_id, " +
        "recorded_at) values ('$tenant', '$ack', '$doc', 2, '$event', now())"

    @Test
    fun `INV-AC-20570 insert and find by tenant and ack id`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) {
            it.insert(TenantId(tenant), "e1", "A1", doc, 2, at) shouldBe true
            it.insert(TenantId(tenant), "e2", "A1", doc, 2, at) shouldBe false
            it.insert(TenantId(tenant), "e1", "A2", doc, 2, at) shouldBe false
        }
        withStore(tenant) { it.find(TenantId(tenant), "A1") } shouldBe AcknowledgementLocator("A1", doc, 2)
        withStore(tenant) { it.find(TenantId(tenant), "A2") } shouldBe null
    }

    @Test
    fun `INV-AC-20570 both unique constraints refuse a duplicate by direct insert`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) { it.insert(TenantId(tenant), "e1", "A1", doc, 2, at) }
        refusedAs(
            EvidenceDb.RUNTIME,
            EvidenceDb.RUNTIME_PASSWORD,
            tenant,
            insertSql(tenant, "A1", "e9"),
        ).sqlState shouldBe
            "23505"
        refusedAs(
            EvidenceDb.RUNTIME,
            EvidenceDb.RUNTIME_PASSWORD,
            tenant,
            insertSql(tenant, "A9", "e1"),
        ).sqlState shouldBe
            "23505"
    }

    @Test
    fun `INV-AC-20570 a second tenant finds nothing`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        withStore(a) { it.insert(TenantId(a), "e1", "A1", doc, 2, at) }
        withStore(b) { it.find(TenantId(b), "A1") } shouldBe null
        withStore(b) { it.find(TenantId(a), "A1") } shouldBe null
    }

    @Test
    fun `INV-AC-20570 UPDATE and DELETE fail with CS-22 for the owner and by grant for the runtime role`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) { it.insert(TenantId(tenant), "e1", "A1", doc, 2, at) }
        listOf(
            "update evidence.acknowledgement_locator set version = 3",
            "delete from evidence.acknowledgement_locator",
        ).forEach { sql ->
            refusedAs(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD, tenant, sql).sqlState shouldBe "42501"
            refusedAs(EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD, tenant, sql).message!! shouldContain "CS-22"
        }
    }

    @Test
    fun `INV-AC-20570 evidence_runtime holds SELECT and INSERT only`() {
        EvidenceDb.superuser().use { c ->
            c.createStatement().use {
                it
                    .executeQuery(
                        "select string_agg(privilege_type, ',' order by privilege_type) " +
                            "from information_schema.role_table_grants where grantee = 'evidence_runtime' " +
                            "and table_schema = 'evidence' and table_name = 'acknowledgement_locator'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "INSERT,SELECT"
                    }
            }
        }
    }
}
