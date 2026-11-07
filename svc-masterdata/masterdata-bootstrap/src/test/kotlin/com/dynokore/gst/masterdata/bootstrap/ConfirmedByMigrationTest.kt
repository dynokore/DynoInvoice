package com.dynokore.gst.masterdata.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * INV-T-8118, INV-Q-10647: `V202611081000` lets a confirmation store only the user ID. Rows stored before it keep their
 * `confirmed_role`; the table CHECK now ties CONFIRMED to `confirmed_by` alone, in both directions (fail closed).
 */
class ConfirmedByMigrationTest {
    private val tenant = UUID.fromString("0a000000-0000-4000-8000-00000000000a")

    private fun <T> on(
        url: String,
        block: (Connection) -> T,
    ): T =
        DriverManager
            .getConnection(url, BoundaryFixture.container.username, BoundaryFixture.container.password)
            .use(block)

    private fun insertChange(
        c: Connection,
        ref: String,
        state: String,
        confirmedBy: String?,
        confirmedRole: String?,
    ) = c.createStatement().executeUpdate(
        "insert into masterdata.identity_triple_change (id, tenant_id, change_ref, entity_ref, constitution, " +
            "sender_id, supplier_id, supplier_gstn, effective_from, state, proposed_by, confirmed_by, " +
            "confirmed_role, " +
            "created_at, updated_at, version) values (gen_random_uuid(), '$tenant', '$ref', 'E-1', 'C', 's', 'u', " +
            "'g', '2026-01-01', '$state', 'P', ${confirmedBy?.let { "'$it'" }}, ${confirmedRole?.let { "'$it'" }}, " +
            "now(), now(), 0)",
    )

    private fun insertTriple(
        c: Connection,
        entity: String,
        confirmedRole: String?,
    ) = c.createStatement().executeUpdate(
        "insert into masterdata.identity_triple (id, tenant_id, entity_ref, constitution, sender_id, supplier_id, " +
            "supplier_gstn, effective_from, proposed_by, confirmed_by, confirmed_role, created_at, updated_at, " +
            "version) values (gen_random_uuid(), '$tenant', '$entity', 'C', 's', 'u', 'g', '2026-01-01', 'P', 'Q', " +
            "${confirmedRole?.let { "'$it'" }}, now(), now(), 0)",
    )

    @Test
    fun `a CONFIRMED row stored before the migration keeps its confirmed_role and confirmed_by after it`() {
        val name =
            "mig_before_" +
                UUID
                    .randomUUID()
                    .toString()
                    .take(8)
                    .replace("-", "")
        val url = BoundaryFixture.migratedDatabase(name, "202610210900")
        on(url) { c ->
            insertChange(c, "OLD-1", "CONFIRMED", "IDN-OLD", "entity.admin")
            insertTriple(c, "E-OLD", "tax.approver")
        }
        // migrate the same database to the latest version
        val flyway =
            org.flywaydb.core.Flyway
                .configure()
                .dataSource(url, BoundaryFixture.container.username, BoundaryFixture.container.password)
                .schemas("masterdata")
                .defaultSchema("masterdata")
                .locations("filesystem:../masterdata-adapters/src/main/resources/db/migration")
                .load()
        flyway.migrate()
        on(url) { c ->
            val change =
                c.createStatement().executeQuery(
                    "select confirmed_by, confirmed_role from masterdata.identity_triple_change " +
                        "where change_ref = 'OLD-1'",
                )
            change.next()
            assertThat(change.getString(1) to change.getString(2)).isEqualTo("IDN-OLD" to "entity.admin")
            val triple =
                c.createStatement().executeQuery(
                    "select confirmed_by, confirmed_role from masterdata.identity_triple where entity_ref = 'E-OLD'",
                )
            triple.next()
            assertThat(triple.getString(1) to triple.getString(2)).isEqualTo("Q" to "tax.approver")
        }
    }

    @Test
    fun `after the migration CONFIRMED needs confirmed_by and a pending row refuses it, with no role`() {
        BoundaryFixture.asSuperuser { c ->
            assertThat(insertChange(c, "NEW-1", "CONFIRMED", "IDN-NEW", null)).isEqualTo(1)
            assertThat(insertTriple(c, "E-NEW", null)).isEqualTo(1)
            assertThat(insertChange(c, "NEW-2", "PENDING_CONFIRMATION", null, null)).isEqualTo(1)
            assertThatThrownBy { insertChange(c, "NEW-3", "CONFIRMED", null, null) }
                .hasMessageContaining("violates check constraint")
            assertThatThrownBy { insertChange(c, "NEW-4", "PENDING_CONFIRMATION", "IDN-NEW", null) }
                .hasMessageContaining("violates check constraint")
            assertThatThrownBy { insertChange(c, "NEW-5", "CONFIRMED", null, "entity.admin") }
                .hasMessageContaining("violates check constraint")
        }
    }
}
