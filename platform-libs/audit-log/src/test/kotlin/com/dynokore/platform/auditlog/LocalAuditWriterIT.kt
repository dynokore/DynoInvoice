package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.KillableTransactionRunner
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [LocalAuditWriter] over [AuditSchema.auditEntryDdl] for a service without an outbox (INV-Q-2958): a fixture
 * `control-plane-svc` with only its `controlplane` schema (no outbox tables) and one state table, on
 * PostgreSQL 17 and 18. Covers the commit-with-the-change rule (INV-REQ-1226, INV-REQ-2588), operator actions
 * under the reserved platform tenant (INV-Q-2927 option D), forced RLS and an insert-only trail (CS-19).
 */
@Tag("integration")
class LocalAuditWriterIT {
    // INV-T-3999 removes this call
    @Suppress("DEPRECATION")
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an entry commits with its change and a rolled-back change leaves no entry`(image: String) {
        ControlPlane(image).use { cp ->
            val tenant = TenantId(UUID.randomUUID())
            cp.tenants.bind(tenant) {
                cp.operations.change(cp.entry("setSupportWindow"), cp.context(tenant, EntityRef("entity-1"))) {
                    cp.setting("support_window", "09-18")
                }
            }
            assertThrows(IllegalStateException::class.java) {
                cp.tenants.bind(tenant) {
                    cp.boundary.inTransaction {
                        cp.setting("support_window", "00-24")
                        cp.audit.record(cp.entry("setSupportWindow"), cp.context(tenant, EntityRef("entity-1")))
                        error("command fails after its entry")
                    }
                }
            }
            assertEquals(1, cp.count("select count(*) from controlplane.audit_entry"))
            assertEquals(1, cp.count("select count(*) from controlplane.setting"))
            assertEquals(
                1,
                cp.count(
                    "select count(*) from controlplane.audit_entry where tenant_id = ? and actor = 'operator-3' " +
                        "and action = 'setSupportWindow' and reason = 'customer request' " +
                        "and prior_state = '{\"window\":\"none\"}'::jsonb",
                    tenant.value,
                ),
            )
        }
    }

    // INV-T-3999 removes this call
    @Suppress("DEPRECATION")
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an operator action with no tenant is recorded under the reserved platform tenant`(image: String) {
        ControlPlane(image).use { cp ->
            val envelope =
                cp.tenants.bindPlatform {
                    cp.operations.change(cp.entry("publishPack"), cp.context(TenantId.PLATFORM, EntityRef.PLATFORM)) {
                        cp.setting("pack", "SG-2026.10")
                    }
                    cp.boundary.inTransaction {
                        cp.audit.record(cp.entry("issueLicence"), cp.context(TenantId.PLATFORM, EntityRef.PLATFORM))
                    }
                }
            assertTrue(envelope.tenant.isPlatform)
            assertEquals(OutboxAuditWriter.EVENT_TYPE, envelope.type)
            assertEquals("/gst/control-plane-svc", envelope.source)
            assertNull(envelope.docSequence)
            assertEquals(
                2,
                cp.count(
                    "select count(*) from controlplane.audit_entry where tenant_id = ? and entity_ref = ?",
                    TenantId.PLATFORM.value,
                    EntityRef.PLATFORM.value,
                ),
            )
            // A tenant session never sees the platform rows, and cannot write one.
            val tenant = TenantId(UUID.randomUUID())
            assertEquals(0, cp.visible(tenant))
            assertThrows(AuditWriteFailed::class.java) {
                cp.tenants.bind(tenant) {
                    cp.boundary.inTransaction {
                        cp.audit.record(cp.entry("issueLicence"), cp.context(TenantId.PLATFORM, EntityRef.PLATFORM))
                    }
                }
            }
            assertEquals(2, cp.count("select count(*) from controlplane.audit_entry"))
        }
    }

    // INV-T-3999 removes this call
    @Suppress("DEPRECATION")
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the trail is tenant-isolated, insert-only and refuses a row for another tenant`(image: String) {
        ControlPlane(image).use { cp ->
            val owner = TenantId(UUID.randomUUID())
            cp.tenants.bind(owner) {
                cp.boundary.inTransaction { cp.audit.record(cp.entry("grantRole"), cp.context(owner, EntityRef("e"))) }
            }
            assertEquals(1, cp.visible(owner))
            assertEquals(0, cp.visible(TenantId(UUID.randomUUID())))
            // INV-T-3999 removes this call
            @Suppress("DEPRECATION")
            fun run(sql: String) = cp.tenants.bind(owner) {
                cp.boundary.inTransaction { cp.boundary.require().createStatement().use { it.executeUpdate(sql) } }
            }
            assertThrows(SQLException::class.java) { run("update controlplane.audit_entry set reason = 'edited'") }
            assertThrows(SQLException::class.java) { run("delete from controlplane.audit_entry") }
            // The context names tenant B but the session is bound to A: the policy's WITH CHECK refuses the row.
            assertThrows(AuditWriteFailed::class.java) {
                cp.tenants.bind(owner) {
                    cp.boundary.inTransaction {
                        cp.audit.record(cp.entry("grantRole"), cp.context(TenantId(UUID.randomUUID()), EntityRef("e")))
                    }
                }
            }
            assertEquals(1, cp.count("select count(*) from controlplane.audit_entry"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the table has forced RLS, tenant-led indexes and classified columns`(image: String) {
        ControlPlane(image).use { cp ->
            assertEquals(
                1,
                cp.count(
                    "select count(*) from pg_class where oid = 'controlplane.audit_entry'::regclass " +
                        "and relrowsecurity and relforcerowsecurity",
                ),
            )
            assertEquals(
                0,
                cp.count(
                    "select count(*) from pg_index i join pg_attribute a on a.attrelid = i.indrelid " +
                        "and a.attnum = i.indkey[0] where i.indrelid = 'controlplane.audit_entry'::regclass " +
                        "and a.attname <> 'tenant_id'",
                ),
                "tenant_id leads every index",
            )
            assertEquals(
                3,
                cp.count(
                    "select count(*) from pg_description d join pg_attribute a on a.attrelid = d.objoid " +
                        "and a.attnum = d.objsubid where d.objoid = 'controlplane.audit_entry'::regclass " +
                        "and d.description like 'class: %'",
                ),
            )
        }
    }

    // INV-T-3999 removes this call
    @Suppress("DEPRECATION")
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a failed entry commits no change and a bad prior state writes nothing`(image: String) {
        ControlPlane(image).use { cp ->
            val tenant = TenantId(UUID.randomUUID())
            cp.fixture.runAsAdmin("revoke insert on controlplane.audit_entry from ${OutboxPostgresFixture.APP}")
            assertThrows(AuditWriteFailed::class.java) {
                cp.tenants.bind(tenant) {
                    cp.operations.change(cp.entry("managePlan"), cp.context(tenant, EntityRef("e"))) {
                        cp.setting("plan", "gold")
                    }
                }
            }
            assertEquals(0, cp.count("select count(*) from controlplane.setting"))
            assertThrows(IllegalArgumentException::class.java) {
                cp.tenants.bind(tenant) {
                    cp.boundary.inTransaction {
                        cp.audit.record(cp.entry("managePlan", prior = "[1]"), cp.context(tenant, EntityRef("e")))
                    }
                }
            }
            assertEquals(0, cp.count("select count(*) from controlplane.audit_entry"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `stage builds the row that record writes and inserts nothing`(image: String) {
        ControlPlane(image).use { cp ->
            val tenant = TenantId(UUID.randomUUID())
            val context = cp.context(tenant, EntityRef("entity-1"))
            val staged =
                cp.tenants.bind(tenant, "operator-3") {
                    cp.boundary.inTransaction { cp.audit.stage(cp.entry("grantRole"), context) }
                }
            assertEquals(0, cp.count("select count(*) from controlplane.audit_entry"), "stage inserts nothing")
            val recorded =
                cp.tenants.bind(tenant, "operator-3") {
                    cp.boundary.inTransaction { cp.audit.record(cp.entry("grantRole"), context) }
                }
            // Every field but the fresh UUIDv7 id is what record wrote (fixed clock).
            assertEquals(recorded, staged.envelope.copy(id = recorded.id))
            assertEquals(7, UUID.fromString(staged.envelope.id.value).version())
            assertEquals(7, UUID.fromString(recorded.id.value).version())
            assertEquals(cp.entry("grantRole"), staged.entry)
            assertEquals(1, cp.rowsLike(recorded.id.value, staged), "record wrote the staged row")
        }
    }

    /** The fixture `control-plane-svc`: its `controlplane` schema holds the audit table and one state table. */
    private class ControlPlane(image: String) : AutoCloseable {
        val fixture = OutboxPostgresFixture(image)
        val tenants = TenantContextImpl()
        val boundary = KillableTransactionRunner(fixture.appDataSource(), tenants)
        val audit = LocalAuditWriter(boundary, SCHEMA, "control-plane-svc", "control-plane-svc-1", CLOCK)
        val operations = TransactionalAuditedOperations(boundary, audit)

        init {
            fixture.runAsAdmin(
                "create schema $SCHEMA authorization outbox_owner; " +
                    "grant usage on schema $SCHEMA to ${OutboxPostgresFixture.APP};",
            )
            fixture.run(
                AuditSchema.auditEntryDdl(SCHEMA, OutboxPostgresFixture.APP) +
                    "\ncreate table $SCHEMA.setting (name text not null, value text not null);" +
                    "\ngrant select, insert on $SCHEMA.setting to ${OutboxPostgresFixture.APP};",
            )
        }

        fun entry(
            action: String,
            prior: String = """{"window":"none"}""",
        ) = AuditEntry("operator-3", action, "installation", "customer request", prior)

        fun context(
            tenant: TenantId,
            entity: EntityRef,
        ) = EventContext(
            tenant = tenant,
            entity = entity,
            docUuid = null,
            transactionDate = null,
            aggregateKey = "controlplane",
            aggregateVersion = null,
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cmd-1"),
        )

        fun setting(
            name: String,
            value: String,
        ) {
            boundary.require().prepareStatement("insert into $SCHEMA.setting values (?, ?)").use {
                it.setString(1, name)
                it.setString(2, value)
                it.executeUpdate()
            }
        }

        // INV-T-3999 removes this call
        @Suppress("DEPRECATION")
        fun visible(tenant: TenantId): Long = tenants.bind(tenant) {
            boundary.inTransaction {
                boundary.require().createStatement().use { s ->
                    s.executeQuery("select count(*) from $SCHEMA.audit_entry").use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }
        }

        /** Rows with id [id] whose every other column is [staged]'s. */
        fun rowsLike(
            id: String,
            staged: StagedAuditEntry,
        ): Long = count(
            "select count(*) from controlplane.audit_entry where id = ?::uuid and tenant_id = ? " +
                "and entity_ref = ? and recorded_at = ? and source = ? and subject = ? and doc_uuid is null " +
                "and transaction_date is null and instance_id = ? and correlation_id = ? " +
                "and causation_id = ? and actor = ? and action = ? and scope = ? and reason = ? " +
                "and prior_state = ?::jsonb",
            id,
            staged.envelope.tenant.value,
            staged.envelope.entity.value,
            Timestamp.from(staged.envelope.time),
            staged.envelope.source,
            staged.envelope.subject,
            staged.envelope.instanceId,
            staged.envelope.correlation.value,
            staged.envelope.causation.value,
            staged.entry.actor,
            staged.entry.action,
            staged.entry.scope,
            checkNotNull(staged.entry.reason),
            checkNotNull(staged.priorStateJson),
        )

        fun count(
            sql: String,
            vararg args: Any,
        ): Long = fixture.adminDataSource().connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

        override fun close() {
            fixture.close()
        }

        companion object {
            const val SCHEMA = "controlplane"
            val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
        }
    }
}
