package com.dynokore.gst.transport.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** The ledger and the owner function at the database, through the runtime login with a signed binding (CS-19). */
class ApNotificationTablesTest {
    private fun <T> runtime(block: (Connection) -> T): T =
        TransportDb.connect(TransportDb.RUNTIME, TransportDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } finally {
                connection.rollback()
            }
        }

    private fun Connection.insertLedger(
        tenant: UUID,
        adapter: String,
        id: String,
        kind: String = "ACKNOWLEDGEMENT",
    ): Int =
        prepareStatement(
            "insert into transport.ap_notification_ledger " +
                "(adapter_id, ap_notification_id, tenant_id, entity_ref, kind) " +
                "values (?, ?, ?, 'E-001', ?) on conflict (adapter_id, ap_notification_id) do nothing",
        ).use {
            it.setString(1, adapter)
            it.setString(2, id)
            it.setObject(3, tenant)
            it.setString(4, kind)
            it.executeUpdate()
        }

    private fun Connection.count(sql: String): Int =
        prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun Connection.ledgerRows(id: String): Int =
        count("select count(*) from transport.ap_notification_ledger where ap_notification_id = '$id'")

    private fun attempt(
        tenant: UUID,
        doc: UUID,
        startedAt: String = "now()",
        entity: String = "E-001",
    ) = runtime { connection ->
        SignedBindings.bind(connection, tenant)
        connection
            .prepareStatement(
                "insert into transport.submission_attempt (id, tenant_id, entity_ref, doc_uuid, version, adapter_id, " +
                    "started_at) values (?, ?, ?, ?, 1, 'primary', $startedAt)",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, tenant)
                it.setString(3, entity)
                it.setObject(4, doc)
                it.executeUpdate()
            }
    }

    private fun ownerOf(doc: UUID): Pair<UUID, String>? =
        runtime { connection ->
            connection.prepareStatement("select owner_tenant, owner_entity from transport.attempt_owner(?)").use {
                it.setObject(1, doc)
                it.executeQuery().use { rows ->
                    if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getString(2) else null
                }
            }
        }

    @Test
    fun `the same adapter and notification id twice gives one ledger row`() {
        val tenant = UUID.randomUUID()
        val id = "N-${UUID.randomUUID()}"
        runtime { connection ->
            SignedBindings.bind(connection, tenant)
            connection.insertLedger(tenant, "primary", id) shouldBe 1
            connection.insertLedger(tenant, "primary", id) shouldBe 0
            connection.ledgerRows(id) shouldBe 1
        }
    }

    @Test
    fun `the same notification id on two adapters gives two rows`() {
        val tenant = UUID.randomUUID()
        val id = "N-${UUID.randomUUID()}"
        runtime { connection ->
            SignedBindings.bind(connection, tenant)
            connection.insertLedger(tenant, "primary", id) shouldBe 1
            connection.insertLedger(tenant, "secondary", id) shouldBe 1
            connection.ledgerRows(id) shouldBe 2
        }
    }

    @Test
    fun `a ledger insert with no bound tenant is refused by row level security`() {
        runtime { connection ->
            shouldThrow<SQLException> {
                connection.insertLedger(UUID.randomUUID(), "primary", "N-${UUID.randomUUID()}")
            }
        }
    }

    @Test
    fun `a ledger row of another tenant is invisible to the bound one`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val id = "N-${UUID.randomUUID()}"
        runtime { connection ->
            SignedBindings.bind(connection, a)
            connection.insertLedger(a, "primary", id) shouldBe 1
        }
        runtime { connection ->
            SignedBindings.bind(connection, b)
            connection.ledgerRows(id) shouldBe 0
        }
    }

    @Test
    fun `a ledger insert of the kind INBOUND_NETWORK_DOCUMENT is refused`() {
        val tenant = UUID.randomUUID()
        runtime { connection ->
            SignedBindings.bind(connection, tenant)
            shouldThrow<SQLException> {
                connection.insertLedger(tenant, "primary", "N-${UUID.randomUUID()}", "INBOUND_NETWORK_DOCUMENT")
            }
        }
    }

    @Test
    fun `attempt_owner returns the tenant of a Doc UUID attempted by another tenant`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val doc = UUID.randomUUID()
        attempt(owner, doc, entity = "E-777")
        runtime { connection ->
            SignedBindings.bind(connection, other)
            connection.prepareStatement("select owner_tenant, owner_entity from transport.attempt_owner(?)").use {
                it.setObject(1, doc)
                it.executeQuery().use { rows ->
                    rows.next() shouldBe true
                    rows.getObject(1, UUID::class.java) shouldBe owner
                    rows.getString(2) shouldBe "E-777"
                }
            }
        }
    }

    @Test
    fun `attempt_owner returns the first attempt when several tenants and attempts exist`() {
        val first = UUID.randomUUID()
        val later = UUID.randomUUID()
        val doc = UUID.randomUUID()
        attempt(first, doc, "now() - interval '1 hour'", "E-FIRST")
        attempt(later, doc, "now()", "E-LATER")
        ownerOf(doc) shouldBe (first to "E-FIRST")
    }

    @Test
    fun `attempt_owner of an unknown Doc UUID returns no row`() {
        ownerOf(UUID.randomUUID()) shouldBe null
    }

    @Test
    fun `the runtime login can execute the function and cannot select another tenant's attempt directly`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val doc = UUID.randomUUID()
        attempt(owner, doc)
        ownerOf(doc)?.first shouldBe owner
        runtime { connection ->
            SignedBindings.bind(connection, other)
            connection.count("select count(*) from transport.submission_attempt where doc_uuid = '$doc'") shouldBe 0
        }
    }

    @Test
    fun `the function is not executable by public`() {
        TransportDb.superuser().use { connection ->
            connection.count(
                "select count(*) from information_schema.routine_privileges where routine_schema = 'transport' " +
                    "and routine_name = 'attempt_owner' and grantee = 'PUBLIC'",
            ) shouldBe 0
        }
    }

    @Test
    fun `the table comment of ap_poll_cursor is the platform-scoped declaration`() {
        TransportDb.superuser().use { connection ->
            connection
                .prepareStatement("select obj_description('transport.ap_poll_cursor'::regclass, 'pg_class')")
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getString(1) shouldBe "platform-scoped: no tenant data"
                    }
                }
        }
    }
}
