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
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

private class WritePostgres(
    image: DockerImageName,
) : PostgreSQLContainer<WritePostgres>(image)

/** One event the fakes wrote, read back as the superuser. */
data class StoredEvent(
    val kind: String,
    val type: String,
    val tenant: UUID,
    val entityRef: String,
    val dataJson: String,
)

/**
 * A PostgreSQL container (16, 17 or 18) with this module's tables and functions in `svc`, created by
 * [ConfigSchema.ddl] in [mode] as the service's migration login would, after the tables that mode writes into
 * (outbox-and-queue's outbox, or audit-log's local `audit_entry`). The runtime login `config_app` is the only login a
 * writer uses; `config_sys` owns the functions.
 *
 * A tenant-and-below change goes to a `test_event` table through the fake [AuditWriter] and [OutboxWriter] on the
 * caller's connection, so an event commits or rolls back with the change exactly as the real outbox would. An
 * installation value is written by [ConfigSchema.WRITE_INSTALLATION_SETTING] with rows staged by the real stage calls
 * ([installationAudit], [installationOutbox]) into the real outbox or `audit_entry`.
 */
class WriteHarness(
    image: String,
    val mode: EventMode = EventMode.OUTBOX,
) : AutoCloseable {
    private val container =
        WritePostgres(DockerImageName.parse(image)).withInitScript("db/config-resolver-test-roles.sql")

    init {
        container.start()
        ds("config_owner", "config_owner_pw").connection.use { c ->
            c.createStatement().use {
                when (mode) {
                    EventMode.OUTBOX -> {
                        it.execute(OutboxSchema.ddl(SCHEMA, RUNTIME, SYS))
                    }

                    EventMode.NO_OUTBOX -> {
                        it.execute(AuditSchema.auditEntryDdl(SCHEMA, RUNTIME))
                        it.execute(AuditSchema.purgeDdl(SCHEMA, RUNTIME, SYS, SERVICE))
                    }
                }
                it.execute(ConfigSchema.ddl(SCHEMA, RUNTIME, SYS, mode))
                it.execute(
                    """
                    create table $SCHEMA.test_event (
                        seq bigint generated always as identity primary key,
                        kind text not null, type text not null, tenant_id uuid not null, entity_ref text not null,
                        data jsonb not null);
                    grant select, insert on $SCHEMA.test_event to $RUNTIME;
                    """.trimIndent(),
                )
            }
        }
    }

    val tenants = TenantContextImpl()
    val app = TransactionRunnerImpl(TenantAwareDataSource(ds(RUNTIME, "config_app_pw")), tenants)
    val admin: DataSource = ds(container.username, container.password)

    /** The migration login that owns the schema and every table (`<svc>_migrate`), for direct-insert CHECK probes. */
    val owner: DataSource = ds("config_owner", "config_owner_pw")
    private val runtimeLogin: DataSource = ds(RUNTIME, "config_app_pw")

    /** [block] on a raw runtime-login connection in one transaction, committed when it returns, with nothing bound. */
    fun <T> runtime(block: (java.sql.Connection) -> T): T =
        runtimeLogin.connection.use { c ->
            c.autoCommit = false
            try {
                block(c).also { c.commit() }
            } catch (failure: Exception) {
                c.rollback()
                throw failure
            }
        }

    /** Fails the next audit write or stage when set. */
    @Volatile var failAudit = false

    fun audit(connections: CurrentConnection): AuditWriter =
        object : AuditWriter {
            override fun record(
                entry: AuditEntry,
                context: EventContext,
            ): Envelope {
                if (failAudit) throw AuditWriteFailed(IllegalStateException("test"))
                return insert(connections, "audit", AUDIT, context, auditJson(entry))
            }
        }

    fun outbox(connections: CurrentConnection): OutboxWriter =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> = events.map { insert(connections, "event", it.type, context, it.dataJson) }
        }

    /** The service's real outbox writer (outbox mode), staging on [clock]. */
    fun installationOutbox(
        connections: CurrentConnection,
        clock: Clock = CLOCK,
    ): OutboxWriter = JdbcOutboxWriter(connections, SCHEMA, SERVICE, INSTANCE, CATALOGUE, clock)

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
                EventMode.NO_OUTBOX -> LocalAuditWriter(connections, SCHEMA, SERVICE, INSTANCE, clock)
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
            connections = app,
            catalogue = catalogue,
            form = form,
            schema = SCHEMA,
            audit = if (platform) installationAudit(app, clock) else audit(app),
            outbox =
                when {
                    !withOutbox -> null
                    installation -> installationOutbox(app, clock)
                    platform -> null
                    else -> outbox(app)
                },
            clock = clock,
            delegation = delegation,
            backdating = backdating,
            constraints = constraints,
        )
    }

    fun reader(form: DeploymentForm = DeploymentForm.SAAS): PolicyReader {
        val catalogue = WriteCatalogue.catalogue
        return JdbcPolicyReader(app, catalogue, form, SCHEMA)
    }

    fun recorder() = JdbcResolutionRecorder(app, SCHEMA, CLOCK)

    fun history(catalogue: VariationPointCatalogue = WriteCatalogue.catalogue): PolicyHistory {
        val form = DeploymentForm.SAAS
        return JdbcPolicyHistory(app, catalogue, form, SCHEMA)
    }

    fun purge() = JdbcConfigPurge(app, SCHEMA)

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

    fun count(sql: String): Long =
        admin.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /**
     * The events stored for [tenant], in order: a tenant's from `test_event`; the platform tenant's (an installation
     * value's) from the real outbox, or in no-outbox mode from `audit_entry` as the audit payload.
     */
    fun events(tenant: TenantId): List<StoredEvent> =
        admin.connection.use { c ->
            val sql =
                when {
                    !tenant.isPlatform -> TEST_EVENTS
                    mode == EventMode.OUTBOX -> OUTBOX_EVENTS
                    else -> AUDIT_ENTRIES
                }
            c.prepareStatement(sql).use { st ->
                st.setObject(1, tenant.value)
                st.executeQuery().use { rows ->
                    generateSequence {
                        if (rows.next()) {
                            StoredEvent(
                                rows.getString(1),
                                rows.getString(2),
                                rows.getObject(3, UUID::class.java),
                                rows.getString(4),
                                rows.getString(5),
                            )
                        } else {
                            null
                        }
                    }.toList()
                }
            }
        }

    private fun insert(
        connections: CurrentConnection,
        kind: String,
        type: String,
        context: EventContext,
        json: String,
    ): Envelope {
        connections
            .require()
            .prepareStatement(
                "insert into $SCHEMA.test_event (kind, type, tenant_id, entity_ref, data) values (?, ?, ?, ?, ?)",
            ).use {
                it.setString(1, kind)
                it.setString(2, type)
                it.setObject(3, context.tenant.value)
                it.setString(4, context.entity.value)
                it.setObject(5, json, Types.OTHER)
                it.executeUpdate()
            }
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
        container.stop()
    }

    private fun ds(
        user: String,
        password: String,
    ): DataSource =
        PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            this.user = user
            this.password = password
        }

    companion object {
        const val SCHEMA = "svc"
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

        private const val TEST_EVENTS =
            "select kind, type, tenant_id, entity_ref, data::text from svc.test_event where tenant_id = ? order by seq"
        private const val OUTBOX_EVENTS =
            "select case when type = '$AUDIT' then 'audit' else 'event' end, type, tenant_id, entity_ref, data::text " +
                "from svc.outbox where tenant_id = ? order by seq"
        private const val AUDIT_ENTRIES =
            "select 'audit', '$AUDIT', tenant_id, entity_ref, jsonb_strip_nulls(jsonb_build_object('actor', actor, " +
                "'action', action, 'scope', scope, 'reason', reason, 'priorState', prior_state))::text " +
                "from svc.audit_entry where tenant_id = ? order by recorded_at, id"

        /** 2026-10-01 08:00 in Singapore. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
        val CORRELATION = CorrelationId("corr-1")
        val CAUSATION = CausationId("cause-1")

        fun clockAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
    }
}
