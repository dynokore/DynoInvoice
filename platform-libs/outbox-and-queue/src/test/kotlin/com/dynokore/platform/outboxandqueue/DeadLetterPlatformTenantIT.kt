package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/**
 * Dead letters without a tenant (coordinator ruling INV-Q-4819, INV-T-4134; joins INV-Q-4185 and INV-T-2997): stored
 * under the platform tenant, read as `tenant = null`, and out of reach of every SELECT of the runtime login whatever
 * tenant it is bound to, so a platform-bound session never reads their `raw` (INV-Q-2962, INV-Q-3130).
 */
@Tag("integration")
class DeadLetterPlatformTenantIT {
    private val tenant = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val platform = TenantId.PLATFORM.value

    private fun record(
        harness: OutboxHarness,
        tenant: UUID?,
        raw: String,
    ): Long =
        harness.fixture.runtimeDataSource().connection.use { c ->
            c.prepareStatement("select submission.${OutboxSchema.DEAD_LETTER_RECORD}(?, ?, ?, ?, ?, ?)").use {
                it.setString(1, "submission-svc")
                it.setString(2, "gst.canonical.canonical-version-created.v1")
                it.setLong(3, 1)
                it.setObject(4, tenant, Types.OTHER)
                it.setString(5, "handler schema mismatch")
                it.setString(6, raw)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    /** The first column of every row [sql] returns in a runtime-login transaction bound to [bound]. */
    private fun bound(
        harness: OutboxHarness,
        bound: UUID,
        sql: String,
    ): List<String?> =
        harness.fixture.runtimeDataSource().connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                    it.setString(1, bound.toString())
                    it.executeQuery().close()
                }
                c.createStatement().use { s ->
                    s.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
            } finally {
                c.rollback()
            }
        }

    private fun storedTenant(
        harness: OutboxHarness,
        id: Long,
    ): String? =
        harness.fixture.adminDataSource().connection.use { c ->
            c.prepareStatement("select tenant_id::text from submission.event_dead_letter where id = ?").use {
                it.setLong(1, id)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a letter with a tenant keeps it, one without or with the platform tenant is stored under it and read as null`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val triage =
                JdbcDeadLetterTriage(
                    harness.fixture.runtimeDataSource(),
                    harness.submission.scope,
                    "submission",
                    MutableClock(),
                )
            val raw = "{\"subject\":\"s-1\"}"
            // (1) a record with tenant T
            val withTenant = record(harness, tenant, raw)
            assertEquals(tenant.toString(), storedTenant(harness, withTenant))
            assertEquals(TenantId(tenant), triage.get(withTenant)?.tenant)
            assertEquals(
                listOf(raw),
                bound(harness, tenant, "select raw from submission.event_dead_letter where id = $withTenant"),
            )
            // (2) a null tenant, and (3) the platform tenant given explicitly
            listOf(null, platform).forEach { given ->
                val id = record(harness, given, "{\"another tenant content\":1}")
                assertEquals(platform.toString(), storedTenant(harness, id))
                val letter = triage.get(id)
                assertNull(letter?.tenant)
                assertNull(letter?.raw)
                assertEquals(false, triage.list(DeadLetterScope(subject = "s-1")).any { it.id == id })
                assertEquals(false, triage.list(DeadLetterScope(entity = "entity-1")).any { it.id == id })
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `no session of the runtime login selects a platform-tenant letter, the sys-owned view still lists it`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val own = record(harness, tenant, "{\"own\":1}")
            val platformLetter = record(harness, null, "{\"another tenant content\":1}")
            // (4) a NULL tenant_id is refused by the table itself
            val insertNull =
                "insert into submission.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw) " +
                    "values ('c', 't', 1, null, 'r', '{}')"
            val refused = assertThrows(SQLException::class.java) { harness.fixture.runAsAdmin(insertNull) }
            assertEquals(
                "23502",
                generateSequence<Throwable>(refused) { it.cause }
                    .filterIsInstance<SQLException>()
                    .mapNotNull { it.sqlState }
                    .first(),
            )
            val table = "submission.event_dead_letter"
            // (5) bound to the platform tenant: 0 rows (the policy, not the tenant policy, hides the letter)
            assertEquals(listOf("0"), bound(harness, TenantId.PLATFORM.value, "select count(*) from $table"))
            assertEquals(
                emptyList<String?>(),
                bound(harness, TenantId.PLATFORM.value, "select raw from $table where id = $platformLetter"),
            )
            // the view, owned by the sys role, still lists it; look-alike: bound to T, T's own letter is read
            val view = "submission.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW}"
            assertEquals(
                listOf("1"),
                bound(harness, TenantId.PLATFORM.value, "select count(*) from $view where id = $platformLetter"),
            )
            assertEquals(listOf("{\"own\":1}"), bound(harness, tenant, "select raw from $table where id = $own"))
            // (6) bound to another tenant U: neither T's nor the platform tenant's letter
            assertEquals(listOf("0"), bound(harness, other, "select count(*) from $table"))
        }
    }
}
