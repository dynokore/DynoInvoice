package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * The build-only stage call of [OutboxWriter] (coordinator ruling INV-Q-4254 A) and the sys role's `INSERT` on the
 * outbox it serves, on PostgreSQL 17 and 18: a staged row is the row `append` writes, and staging writes nothing; the
 * sys role inserts only rows its bound tenant's policy admits.
 */
@Tag("integration")
class OutboxStageIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a staged platform row is the row append writes, and staging writes nothing`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val context = platformContext("stage-${UUID.randomUUID()}")
            val event = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, """{"who":"operator"}""")

            val staged = kit.inPlatformTx { kit.writer.stage(listOf(event), context) }.single()
            assertEquals(0, harness.rows("payload.outbox", "subject", context.aggregateKey), "stage wrote a row")

            val written = kit.inPlatformTx { kit.writer.append(listOf(event), context) }.single()
            // Same clock, same context: only the UUIDv7's random bits differ.
            assertEquals(written, staged.envelope.copy(id = written.id))
            assertEquals("gst.platform.audit-entry-recorded.v1", staged.topic)
            assertEquals(
                1,
                harness.count(
                    "select count(*) from payload.outbox where id = ? and topic = ?",
                    written.id.value,
                    staged.topic,
                ),
            )
            assertEquals(null, staged.envelope.docSequence)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `stage refuses a domain event, a sequenced context and an uncatalogued type`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val platform = platformContext("stage-refused")
            val domain = OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")
            assertThrows(IllegalArgumentException::class.java) {
                val document = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
                kit.inTx(ServiceKit.TENANT) { kit.writer.stage(listOf(domain), document) }
            }
            val audit = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, "{}")
            assertThrows(IllegalArgumentException::class.java) {
                kit.inPlatformTx { kit.writer.stage(listOf(audit), platform.copy(aggregateVersion = 1)) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                kit.inPlatformTx { kit.writer.stage(listOf(audit, domain), platform) }
            }
            // PolicyChanged is a platform type, but not in this test catalogue (INV-AC-1869).
            assertThrows(EmissionNotPermitted::class.java) {
                kit.inPlatformTx { kit.writer.stage(listOf(OutboundEvent(POLICY_CHANGED, "{}")), platform) }
            }
            assertEquals(0, harness.rows("payload.outbox", "subject", platform.aggregateKey))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the sys role inserts an outbox row only under its bound tenant`(image: String) {
        OutboxHarness(image, payloadDdl = PROBE_DDL).use { harness ->
            val fixture = harness.fixture
            val app = {
                TenancyProbe.connect(
                    fixture.jdbcUrl(),
                    OutboxPostgresFixture.APP,
                    OutboxPostgresFixture.APP_PASSWORD,
                )
            }
            assertEquals(PROBE_CATALOGUE, probeCatalogue(app()))

            val platformId = "platform-row-${UUID.randomUUID()}"
            val inserted =
                JooqKit.Access.runtime(fixture, PROBE_TABLES).use {
                    it.asPlatform { dsl ->
                        callProbe(dsl, platformId, TenantId.PLATFORM.value)
                    }
                }
            assertEquals(1, inserted)
            assertEquals(1L, rowsWithId(app(), platformId))

            val foreignId = "foreign-row-${UUID.randomUUID()}"
            val foreign = refusedBound(fixture, foreignId, UUID.randomUUID())
            assertEquals("42501", JooqKit.sqlState(foreign), foreign?.message)
            assertEquals(0L, rowsWithId(app(), foreignId))

            val unboundId = "unbound-row-${UUID.randomUUID()}"
            val unbound = refusedUnbound(app(), unboundId)
            assertEquals("42501", JooqKit.sqlState(unbound), unbound?.message)
            assertEquals(0L, rowsWithId(app(), unboundId))
        }
    }

    /** Bound to the platform tenant, another tenant's row is refused by the tenant policy. */
    private fun refusedBound(
        fixture: OutboxPostgresFixture,
        id: String,
        tenant: UUID,
    ): Throwable? =
        JooqKit.Access.runtime(fixture, PROBE_TABLES).use { access ->
            runCatching { access.asPlatform { callProbe(it, id, tenant) } }.exceptionOrNull()
        }

    /** Nothing bound: no row at all. */
    private fun refusedUnbound(
        probe: TenancyProbe,
        id: String,
    ): Throwable? {
        val platform = TenantId.PLATFORM.value.toString()
        return runCatching {
            probe.unbound {
                fetchValue(
                    "select payload.$PROBE(?, ?::uuid)",
                    id,
                    platform,
                )
            }
        }.exceptionOrNull()
    }

    /** The owner, definer flag, search_path and non-owner grantees of the probe function, from pg_proc. */
    private fun probeCatalogue(probe: TenancyProbe): Map<String, Any?> =
        probe
            .unbound {
                fetchRows(
                    "select pg_get_userbyid(p.proowner) as owner, p.prosecdef as definer, " +
                        "array_to_string(p.proconfig, ';') as config, " +
                        "(select string_agg(a.privilege_type || ':' || case when a.grantee = 0 then 'PUBLIC' " +
                        "else pg_get_userbyid(a.grantee) end, ',' order by a.grantee) " +
                        "from aclexplode(p.proacl) a where a.grantee <> p.proowner) as acl " +
                        "from pg_proc p where p.pronamespace = 'payload'::regnamespace and p.proname = ?",
                    PROBE,
                )
            }.single()

    private fun callProbe(
        dsl: DSLContext,
        id: String,
        tenant: UUID,
    ): Int? = dsl.select(probeCall(id, tenant)).fetchOne(0, Int::class.java)

    private fun probeCall(
        id: String,
        tenant: UUID,
    ): Field<Int> =
        DSL.function(
            DSL.name("payload", PROBE),
            SQLDataType.INTEGER,
            DSL.`val`(id, SQLDataType.CLOB),
            DSL.`val`(tenant, SQLDataType.UUID),
        )

    /** The rows of `payload.outbox` with [id], read under the platform binding. */
    private fun rowsWithId(
        probe: TenancyProbe,
        id: String,
    ): Long =
        probe.inTenant(TenantId.PLATFORM) {
            fetchValue("select count(*) from payload.outbox where id = ?", id)
        } as Long

    private fun platformContext(key: String) =
        EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            aggregateKey = key,
            aggregateVersion = null,
            correlation = CorrelationId("corr-$key"),
            causation = CausationId("cmd-$key"),
        )

    private companion object {
        const val POLICY_CHANGED = "com.dynokore.gst.platform.PolicyChanged.v1"
        const val PROBE = "sys_insert_probe"

        val PROBE_CATALOGUE =
            mapOf<String, Any?>(
                "owner" to OutboxPostgresFixture.SYS,
                "definer" to true,
                "config" to "search_path=pg_catalog, payload, pg_temp",
                "acl" to "EXECUTE:${OutboxPostgresFixture.APP}",
            )

        /** The probe function is declared PLATFORM_SCOPED: it reads no tenant table of its own. */
        val PROBE_TABLES = mapOf(DSL.name("payload", PROBE) to TableClass.PLATFORM_SCOPED)

        /**
         * Init SQL of the `payload` schema (INV-Q-5854): a definer function owned by the sys role that inserts one
         * outbox row for a tenant and returns the row count. The sys role holds CREATE only for the hand-over.
         */
        val PROBE_DDL =
            """
            create function payload.$PROBE(p_id text, p_tenant uuid) returns integer
            language plpgsql security definer set search_path = pg_catalog, payload, pg_temp as $$
            declare inserted integer;
            begin
                insert into payload.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref,
                    instance_id, correlation_id, causation_id, data)
                values (p_id, 'gst.platform.audit-entry-recorded.v1', '${TestCatalogue.AUDIT_ENTRY_RECORDED}',
                    '/gst/payload-svc', 'k', now(), p_tenant, '${EntityRef.PLATFORM.value}', 'i', 'c', 'c', '{}');
                get diagnostics inserted = row_count;
                return inserted;
            end
            $$;
            grant create on schema payload to ${OutboxPostgresFixture.SYS};
            alter function payload.$PROBE(text, uuid) owner to ${OutboxPostgresFixture.SYS};
            revoke all on function payload.$PROBE(text, uuid) from public;
            grant execute on function payload.$PROBE(text, uuid) to ${OutboxPostgresFixture.APP};
            revoke create on schema payload from ${OutboxPostgresFixture.SYS};
            """.trimIndent()
    }
}
