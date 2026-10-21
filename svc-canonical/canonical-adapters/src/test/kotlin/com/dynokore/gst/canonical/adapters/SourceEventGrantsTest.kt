package com.dynokore.gst.canonical.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/** INV-AC-7865: the runtime login inserts and selects `source_event`; its UPDATE and DELETE are refused. */
class SourceEventGrantsTest {
    private val tenant = UUID.randomUUID()

    private fun insertRow() =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            connection
                .prepareStatement(
                    "insert into canonical.source_event " +
                        "(tenant_id, source_system, source_key, source_revision, doc_uuid) " +
                        "values (?, 'erp', ?, 1, ?)",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, "k-" + UUID.randomUUID())
                    it.setObject(3, UUID.randomUUID())
                    it.executeUpdate() shouldBe 1
                }
            connection.commit()
        }

    /** The SQL state of the refusal of [statement] as the runtime login, or null when it was allowed. */
    private fun refusal(statement: String): String? =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                connection.prepareStatement(statement).use { it.executeUpdate() }
                null
            } catch (refused: SQLException) {
                refused.sqlState
            } finally {
                connection.rollback()
            }
        }

    private fun rows(): Int =
        CanonicalDb.superuser().use { connection ->
            connection.prepareStatement("select count(*) from canonical.source_event where tenant_id = ?").use {
                it.setObject(1, tenant)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun updateAndDeleteAreRefused(): Boolean =
        refusal("update canonical.source_event set source_system = 'x'") == INSUFFICIENT_PRIVILEGE &&
            refusal("delete from canonical.source_event") == INSUFFICIENT_PRIVILEGE

    @Test
    fun `INV-AC-7865 the runtime login inserts and selects but its UPDATE and DELETE fail`() {
        insertRow()
        updateAndDeleteAreRefused() shouldBe true
        rows() shouldBe 1
    }

    @Test
    fun `INV-AC-7865 probe a scratch grant of UPDATE makes the check fail`() {
        insertRow()
        CanonicalDb.connect(CanonicalDb.MIGRATE, CanonicalDb.MIGRATE_PASSWORD).use { owner ->
            owner.createStatement().use { it.execute("grant update on canonical.source_event to canonical_runtime") }
            try {
                updateAndDeleteAreRefused() shouldBe false // the probe: the committed test would fail here
            } finally {
                owner.createStatement().use {
                    it.execute(
                        "revoke update on canonical.source_event from canonical_runtime",
                    )
                }
            }
        }
        updateAndDeleteAreRefused() shouldBe true
    }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
