package com.dynokore.platform.configresolver

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditSchema
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.AuditWriteFailed
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.LocalAuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.auditlog.StagedAuditEntry
import com.dynokore.platform.configresolver.ConfigSchema.EventMode
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TestPoolFixture
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private class WritePostgres(
    image: DockerImageName,
) : PostgreSQLContainer<WritePostgres>(image)

/** What a refused statement ended with: its SQLSTATE and the database's message. */
data class Refusal(
    val sqlState: String?,
    val message: String,
)

/** One event the fakes wrote, read back as the superuser. */
data class StoredEvent(
    val kind: String,
    val type: String,
    val tenant: UUID,
    val entityRef: String,
    val dataJson: String,
)

/**
 * A PostgreSQL container (16, 17 or 18) with this module's tables and functions in `svc`, created at container start by
 * init SQL (route R1, owner ruling INV-Q-4508 (A)): [ConfigSchema.ddl] in [mode] as the service's migration login would
 * (`SET ROLE config_owner`), after the tables that mode writes into (outbox-and-queue's outbox, or audit-log's local
 * `audit_entry`). No test issues DDL. The runtime login `config_app` is the only login a writer uses; `config_sys` owns
 * the functions. Every login reaches the database through [TestPoolFixture], a transaction runner and tenancy-jooq's
 * DSLContext, or a `TenancyProbe` ([appProbe] for statements the runtime login itself must be refused, [ownerProbe] for
 * reads of rows and of the catalog under a tenant's binding).
 *
 * A tenant-and-below change goes to a `test_event` table through the fake [AuditWriter] and [OutboxWriter] in the
 * caller's transaction, so an event commits or rolls back with the change exactly as the real outbox would. An
 * installation value is written by [ConfigSchema.WRITE_INSTALLATION_SETTING] with rows staged by the real stage calls
 * ([installationAudit], [installationOutbox]) into the real outbox or `audit_entry`.
 */
class WriteHarness(
    image: String,
    val mode: EventMode = EventMode.OUTBOX,
) : AutoCloseable {
    private val container =
        WritePostgres(DockerImageName.parse(image))
            .withCopyToContainer(Transferable.of(rolesScript()), "/docker-entrypoint-initdb.d/00-config-roles.sql")
            .withCopyToContainer(
                Transferable.of(schemaScript(mode)),
                "/docker-entrypoint-initdb.d/10-config-schema.sql",
            ).also { it.start() }

    val tenants = TenantContextImpl()
    private val appPool = TestPoolFixture(container.jdbcUrl, RUNTIME, "config_app_pw", poolName = "write-app")
    private val ownerPool = TestPoolFixture(container.jdbcUrl, OWNER, "config_owner_pw", poolName = "write-owner")
    val app = appPool.runner(tenants)
    private val ownerRunner = ownerPool.runner(tenants)

    /** The module's tables, the fixture `test_event` and the outbox and local audit tables the tests seed and read. */
    private val tables =
        ConfigTables.declared(SCHEMA) +
            mapOf(
                DSL.name(SCHEMA, TEST_EVENT) to TableClass.TENANT,
                DSL.name(SCHEMA, OUTBOX) to TableClass.TENANT,
                DSL.name(SCHEMA, AuditSchema.AUDIT_ENTRY) to TableClass.TENANT,
            )

    /** tenancy-jooq's DSLContext on [app] (owner ruling INV-Q-4196): the purge, recorder and fakes run on it. */
    val dsl = TenancyJooq.dslContext(tables, tenants, app)

    /** The same tables on the migration login's runner, to seed state the runtime login has no grant for. */
    private val ownerDsl = TenancyJooq.dslContext(tables, tenants, ownerRunner)

    /** A `TenancyProbe` on the runtime login, for statements run with a tenant and actor bound as the test says. */
    val appProbe: TenancyProbe = TenancyProbe.connect(container.jdbcUrl, RUNTIME, "config_app_pw")

    /** A `TenancyProbe` on the migration login that owns every table (`<svc>_migrate`): reads and CHECK probes. */
    val ownerProbe: TenancyProbe = TenancyProbe.connect(container.jdbcUrl, OWNER, "config_owner_pw")

    /** Fails the next audit write or stage when set. */
    @Volatile var failAudit = false

    fun audit(): AuditWriter =
        object : AuditWriter {
            override fun record(
                entry: AuditEntry,
                context: EventContext,
            ): Envelope {
                if (failAudit) throw AuditWriteFailed(IllegalStateException("test"))
                return insert("audit", AUDIT, context, auditJson(entry))
            }
        }

    fun outbox(): OutboxWriter =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> = events.map { insert("event", it.type, context, it.dataJson) }
        }

    /** The service's real outbox writer (outbox mode), staging on [clock]. */
    fun installationOutbox(
        connections: CurrentConnection,
        clock: Clock = CLOCK,
    ): OutboxWriter =
        JdbcOutboxWriter(
            TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), tenants, connections as TransactionRunner),
            SCHEMA,
            SERVICE,
            INSTANCE,
            CATALOGUE,
            clock,
        )

    /**
     * The service's real audit writer and stager: [OutboxAuditWriter] over [installationOutbox] in outbox mode,
     * [LocalAuditWriter] otherwise; fails when [failAudit] is set.
     */
    fun installationAudit(
        connections: CurrentConnection,
        clock: Clock = CLOCK,
    ): AuditWriter {
        val real =
            when (mode) {
                EventMode.OUTBOX -> OutboxAuditWriter(installationOutbox(connections, clock))
                EventMode.NO_OUTBOX -> LocalAuditWriter(dsl, SCHEMA, SERVICE, INSTANCE, clock)
            }
        return object : AuditWriter, AuditStager {
            override fun record(
                entry: AuditEntry,
                context: EventContext,
            ): Envelope {
                if (failAudit) throw AuditWriteFailed(IllegalStateException("test"))
                return real.record(entry, context)
            }

            override fun stage(
                entry: AuditEntry,
                context: EventContext,
            ): StagedAuditEntry {
                if (failAudit) throw AuditWriteFailed(IllegalStateException("test"))
                return (real as AuditStager).stage(entry, context)
            }
        }
    }

    fun writer(
        form: DeploymentForm = DeploymentForm.SAAS,
        clock: Clock = CLOCK,
        withOutbox: Boolean = true,
        delegation: DelegationProbe? = DelegationProbe { _, _ -> false },
        backdating: BackdatingFloor? = null,
        constraints: ValueConstraints? = null,
        platform: Boolean = false,
        catalogue: VariationPointCatalogue = WriteCatalogue.catalogue,
    ): JdbcPolicyWriter {
        val installation = platform && mode == EventMode.OUTBOX
        return JdbcPolicyWriter(
            dsl = dsl,
            catalogue = catalogue,
            form = form,
            schema = SCHEMA,
            audit = if (platform) installationAudit(app, clock) else audit(),
            outbox =
                when {
                    !withOutbox -> null
                    installation -> installationOutbox(app, clock)
                    platform -> null
                    else -> outbox()
                },
            clock = clock,
            delegation = delegation,
            backdating = backdating,
            constraints = constraints,
        )
    }

    fun reader(form: DeploymentForm = DeploymentForm.SAAS): PolicyReader {
        val catalogue = WriteCatalogue.catalogue
        return JdbcPolicyReader(dsl, catalogue, form, SCHEMA)
    }

    fun recorder() = JdbcResolutionRecorder(dsl, SCHEMA, CLOCK)

    fun history(catalogue: VariationPointCatalogue = WriteCatalogue.catalogue): PolicyHistory {
        val form = DeploymentForm.SAAS
        return JdbcPolicyHistory(dsl, catalogue, form, SCHEMA)
    }

    fun purge() = JdbcConfigPurge(dsl, SCHEMA)

    /** A transaction of [tenant] on the runtime login with [actor] bound (INV-Q-3997, INV-Q-4268). */
    fun <T> inTenant(
        tenant: TenantId,
        actor: String = ACTOR,
        block: () -> T,
    ): T = tenants.bind(tenant, actor) { app.inTransaction(block) }

    /** A platform transaction on the runtime login with [actor], the setting actor, bound (INV-Q-3997). */
    fun <T> inPlatform(
        actor: String,
        block: () -> T,
    ): T = tenants.bindPlatform(actor) { app.inTransaction(block) }

    /** [writer] sets an installation value in a platform transaction bound to the command's actor. */
    fun putInstallation(
        writer: PolicyWriter,
        cmd: SetPolicyCommand,
    ): PolicyChange = inPlatform(cmd.actor.principal) { writer.setPolicy(cmd) }

    /**
     * The first column of the first row of [sql] as a count, on the migration login under [tenant]'s binding (forced
     * shows a tenant its own rows only; a platform-scoped table and the catalog are not filtered).
     */
    fun count(
        sql: String,
        tenant: TenantId = TenantId.PLATFORM,
    ): Long = ownerProbe.inTenant(tenant) { fetchValue(sql) } as Long

    fun instant(sql: String): Instant =
        when (val value = ownerProbe.unbound { fetchValue(sql) }) {
            is OffsetDateTime -> value.toInstant()
            is java.sql.Timestamp -> value.toInstant()
            else -> error("not a timestamp: $value")
        }

    fun text(sql: String): String? = ownerProbe.unbound { fetchValue(sql) } as String?

    fun bool(sql: String): Boolean = ownerProbe.unbound { fetchValue(sql) } as Boolean

    /**
     * Binds [tenant] and [actor] as given on the runtime login (an absent one as the empty setting, which the
     * transaction runner would refuse) and runs [block] in that one transaction; commits when it returns.
     */
    fun <T> raw(
        tenant: String?,
        actor: String?,
        block: TenancyProbe.() -> T,
    ): T =
        appProbe.unbound {
            fetchValue(
                "select set_config('app.tenant_id', coalesce(?, ''), true), " +
                    "set_config('app.actor', coalesce(?, ''), true)",
                tenant,
                actor,
            )
            block()
        }

    /** The SQLSTATE [error] ends with, also inside a jOOQ exception (tenancy-kernel's `sqlStateOf`). */
    fun sqlStateOf(error: Throwable): String? = app.sqlStateOf(error)

    /** The message of the deepest cause of [error], where the database's own text is. */
    fun rootMessage(error: Throwable): String =
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.last().message.orEmpty()

    /** What [block] was refused with: its SQLSTATE and the database's message; fails when [block] does not throw. */
    fun refusal(block: () -> Unit): Refusal {
        val error = runCatching(block).exceptionOrNull() ?: error("the statement was not refused")
        return Refusal(sqlStateOf(error), rootMessage(error))
    }

    /**
     * Stores a row under [id] in the outbox or local audit table of the platform tenant, so that a later staged row of
     * that id is a duplicate. Seeded as the migration login: the runtime login stores such rows only through the
     * function.
     */
    fun seedTaken(id: String) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        tenants.bindPlatform("seed") {
            ownerRunner.inTransaction {
                if (mode == EventMode.OUTBOX) seedOutbox(id, now) else seedAudit(id, now)
            }
        }
    }

    private fun seedOutbox(
        id: String,
        now: OffsetDateTime,
    ) {
        ownerDsl
            .insertInto(DSL.table(DSL.name(SCHEMA, OUTBOX)))
            .set(col("id", SQLDataType.CLOB), id)
            .set(col("topic", SQLDataType.CLOB), "t")
            .set(col("type", SQLDataType.CLOB), "x")
            .set(col("source", SQLDataType.CLOB), "/gst/x")
            .set(col("subject", SQLDataType.CLOB), "s")
            .set(col("event_time", SQLDataType.TIMESTAMPWITHTIMEZONE), now)
            .set(col("tenant_id", SQLDataType.UUID), TenantId.PLATFORM.value)
            .set(col("entity_ref", SQLDataType.CLOB), "x")
            .set(col("instance_id", SQLDataType.CLOB), "i")
            .set(col("correlation_id", SQLDataType.CLOB), "c")
            .set(col("causation_id", SQLDataType.CLOB), "c")
            .set(col("data", SQLDataType.JSONB), DSL.`val`("{}").cast(SQLDataType.JSONB))
            .execute()
    }

    private fun seedAudit(
        id: String,
        now: OffsetDateTime,
    ) {
        ownerDsl
            .insertInto(DSL.table(DSL.name(SCHEMA, AuditSchema.AUDIT_ENTRY)))
            .set(col("id", SQLDataType.UUID), UUID.fromString(id))
            .set(col("tenant_id", SQLDataType.UUID), TenantId.PLATFORM.value)
            .set(col("entity_ref", SQLDataType.CLOB), "x")
            .set(col("recorded_at", SQLDataType.TIMESTAMPWITHTIMEZONE), now)
            .set(col("source", SQLDataType.CLOB), "/gst/x")
            .set(col("subject", SQLDataType.CLOB), "s")
            .set(col("instance_id", SQLDataType.CLOB), "i")
            .set(col("correlation_id", SQLDataType.CLOB), "c")
            .set(col("causation_id", SQLDataType.CLOB), "c")
            .set(col("actor", SQLDataType.CLOB), "a")
            .set(col("action", SQLDataType.CLOB), "a")
            .set(col("scope", SQLDataType.CLOB), "a")
            .execute()
    }

    /**
     * The events stored for [tenant], in order: a tenant's from `test_event`; the platform tenant's (an installation
     * value's) from the real outbox, or in no-outbox mode from `audit_entry` as the audit payload.
     */
    fun events(tenant: TenantId): List<StoredEvent> {
        val sql =
            when {
                !tenant.isPlatform -> TEST_EVENTS
                mode == EventMode.OUTBOX -> OUTBOX_EVENTS
                else -> AUDIT_ENTRIES
            }
        return ownerProbe.inTenant(tenant) { fetchRows(sql, tenant.value) }.map {
            val columns = it.values.toList()
            StoredEvent(
                columns[0] as String,
                columns[1] as String,
                columns[2] as UUID,
                columns[3] as String,
                columns[4] as String,
            )
        }
    }

    private fun <T> col(
        name: String,
        type: org.jooq.DataType<T>,
    ): Field<T> = DSL.field(DSL.name(name), type)

    private fun insert(
        kind: String,
        type: String,
        context: EventContext,
        json: String,
    ): Envelope {
        dsl
            .insertInto(DSL.table(DSL.name(SCHEMA, TEST_EVENT)))
            .set(col("kind", SQLDataType.CLOB), kind)
            .set(col("type", SQLDataType.CLOB), type)
            .set(col("tenant_id", SQLDataType.UUID), context.tenant.value)
            .set(col("entity_ref", SQLDataType.CLOB), context.entity.value)
            .set(col("data", SQLDataType.JSONB), DSL.`val`(json).cast(SQLDataType.JSONB))
            .execute()
        return Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/test-svc",
            type = type,
            subject = context.aggregateKey,
            time = CLOCK.instant(),
            tenant = context.tenant,
            entity = context.entity,
            docUuid = null,
            transactionDate = null,
            docSequence = 1,
            instanceId = "test",
            correlation = context.correlation,
            causation = context.causation,
            dataJson = json,
        )
    }

    private fun auditJson(entry: AuditEntry): String {
        fun q(v: String?) = if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return "{\"actor\":${q(entry.actor)},\"action\":${q(entry.action)},\"scope\":${q(entry.scope)}," +
            "\"reason\":${q(entry.reason)},\"priorState\":${entry.priorStateJson ?: "null"}}"
    }

    override fun close() {
        appPool.close()
        ownerPool.close()
        container.stop()
    }

    companion object {
        const val SCHEMA = "svc"
        const val TEST_EVENT = "test_event"
        const val OUTBOX = "outbox"
        const val OWNER = "config_owner"
        const val RUNTIME = "config_app"
        const val SYS = "config_sys"
        const val SERVICE = "config-test-svc"
        const val INSTANCE = "config-test-1"

        /** The actor a tenant transaction binds when a test names none. */
        const val ACTOR = "user-1"
        const val AUDIT = "com.dynokore.gst.platform.AuditEntryRecorded.v1"
        const val AUDIT_TOPIC = "gst.platform.audit-entry-recorded.v1"
        const val POLICY_CHANGED_TOPIC = "gst.platform.policy-changed.v1"

        /** The two platform events an installation value writes, produced by every service (INV-REQ-815 b4). */
        val CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry(AUDIT, AUDIT_TOPIC, setOf(CatalogueEntry.EVERY_SERVICE), false, true),
                    CatalogueEntry(
                        JdbcPolicyWriter.POLICY_CHANGED,
                        POLICY_CHANGED_TOPIC,
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        false,
                    ),
                ),
            )

        /** The roles and grants, run by the image's init before [schemaScript]. */
        private fun rolesScript(): String =
            checkNotNull(WriteHarness::class.java.getResource("/db/config-resolver-test-roles.sql")) {
                "no roles script"
            }.readText()

        /** The tables and functions, as `config_owner` creates them in [mode]; run after the roles. */
        private fun schemaScript(mode: EventMode): String {
            val tables =
                when (mode) {
                    EventMode.OUTBOX -> {
                        OutboxSchema.ddl(SCHEMA, RUNTIME, SYS)
                    }

                    EventMode.NO_OUTBOX -> {
                        AuditSchema.auditEntryDdl(SCHEMA, RUNTIME) + "\n" +
                            AuditSchema.purgeDdl(SCHEMA, RUNTIME, SYS, SERVICE)
                    }
                }
            return "SET ROLE $OWNER;\n$tables\n${ConfigSchema.ddl(SCHEMA, RUNTIME, SYS, mode)}\n" +
                "create table $SCHEMA.$TEST_EVENT (\n" +
                "    seq bigint generated always as identity primary key,\n" +
                "    kind text not null, type text not null, tenant_id uuid not null, entity_ref text not null,\n" +
                "    data jsonb not null);\n" +
                "grant select, insert on $SCHEMA.$TEST_EVENT to $RUNTIME;\n" +
                "RESET ROLE;\n"
        }

        private const val TEST_EVENTS =
            "select kind, type, tenant_id, entity_ref, data::text from svc.test_event where tenant_id = ? order by seq"
        private const val OUTBOX_EVENTS =
            "select case when type = '$AUDIT' then 'audit' else 'event' end, type, tenant_id, entity_ref, data::text " +
                "from svc.outbox where tenant_id = ? order by seq"
        private const val AUDIT_ENTRIES =
            "select 'audit' as kind, '$AUDIT' as type, tenant_id, entity_ref, jsonb_strip_nulls(" +
                "jsonb_build_object('actor', actor, 'action', action, 'scope', scope, 'reason', reason, " +
                "'priorState', prior_state))::text as data " +
                "from svc.audit_entry where tenant_id = ? order by recorded_at, id"

        /** 2026-10-01 08:00 in Singapore. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
        val CORRELATION = CorrelationId("corr-1")
        val CAUSATION = CausationId("cause-1")

        fun clockAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
    }
}
