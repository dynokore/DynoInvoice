package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
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

    private val deadLetters = DSL.table(DSL.name("submission", OutboxSchema.DEAD_LETTER))
    private val letterId = DSL.field(DSL.name("id"), SQLDataType.BIGINT)
    private val letterTenant = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)

    private fun record(
        harness: OutboxHarness,
        tenant: UUID?,
        raw: String,
    ): Long =
        JooqKit.recordDeadLetter(
            harness.fixture,
            "submission",
            "submission-svc",
            "gst.canonical.canonical-version-created.v1",
            1,
            tenant,
            "handler schema mismatch",
            raw,
        )

    /** The first column of every row [sql] returns in a runtime-login transaction bound to [bound]. */
    private fun bound(
        harness: OutboxHarness,
        bound: UUID,
        sql: String,
    ): List<String?> =
        TenancyProbe
            .connect(harness.fixture.jdbcUrl(), OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD)
            .inTenant(TenantId(bound)) { fetchRows(sql).map { it.values.first()?.toString() } }

    /** The `tenant_id` of letter [id], read by the owner login bound to [expected] (no row fails the test). */
    private fun storedTenant(
        harness: OutboxHarness,
        id: Long,
        expected: UUID,
    ): String? =
        JooqKit.Access
            .owner(harness.fixture, mapOf(DSL.name("submission", OutboxSchema.DEAD_LETTER) to TableClass.TENANT))
            .use { owner ->
                val read = { dsl: DSLContext ->
                    dsl
                        .select(letterTenant)
                        .from(deadLetters)
                        .where(letterId.eq(id))
                        .fetchSingle()
                        .value1()
                        ?.toString()
                }
                val stored =
                    if (expected == platform) owner.asPlatform(read) else owner.asTenant(expected, read)
                // look-alike: the same id bound to another tenant returns no row
                assertEquals(
                    0,
                    owner.asTenant(
                        other,
                    ) {
                        it
                            .selectCount()
                            .from(deadLetters)
                            .where(letterId.eq(id))
                            .fetchSingle()
                            .value1()
                    },
                )
                stored
            }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a letter with a tenant keeps it, one without or with the platform tenant is stored under it and read as null`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val triage =
                JdbcDeadLetterTriage(
                    harness.submission.scope,
                    harness.submission.dsl,
                    "submission",
                    MutableClock(),
                )
            val raw = "{\"subject\":\"s-1\"}"
            // (1) a record with tenant T
            val withTenant = record(harness, tenant, raw)
            assertEquals(tenant.toString(), storedTenant(harness, withTenant, tenant))
            assertEquals(TenantId(tenant), triage.get(withTenant)?.tenant)
            assertEquals(
                listOf(raw),
                bound(harness, tenant, "select raw from submission.event_dead_letter where id = $withTenant"),
            )
            // (2) a null tenant, and (3) the platform tenant given explicitly
            listOf(null, platform).forEach { given ->
                val id = record(harness, given, "{\"another tenant content\":1}")
                assertEquals(platform.toString(), storedTenant(harness, id, platform))
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
