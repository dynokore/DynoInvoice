package com.dynokore.platform.configresolver

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriteFailed
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.OutboundEvent
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
 * A PostgreSQL container (17 or 18) with this module's tables in `svc`, created by [ConfigSchema.ddl], and
 * a `test_event` table the fake [AuditWriter] and [OutboxWriter] insert into on the caller's connection, so
 * an event commits or rolls back with the change exactly as the real outbox would.
 */
class WriteHarness(
    image: String,
) : AutoCloseable {
    private val container =
        WritePostgres(DockerImageName.parse(image)).withInitScript("db/config-resolver-test-roles.sql")

    init {
        container.start()
        ds("config_owner", "config_owner_pw").connection.use { c ->
            c.createStatement().use {
                it.execute(ConfigSchema.ddl(SCHEMA, "config_app", "config_platform", "config_purge"))
                it.execute(
                    """
                    create table $SCHEMA.test_event (
                        seq bigint generated always as identity primary key,
                        kind text not null, type text not null, tenant_id uuid not null, entity_ref text not null,
                        data jsonb not null);
                    grant select, insert on $SCHEMA.test_event to config_app, config_platform;
                    """.trimIndent(),
                )
            }
        }
    }

    val tenants = TenantContextImpl()
    val app = TransactionRunnerImpl(TenantAwareDataSource(ds("config_app", "config_app_pw")), tenants)
    val platformTx = TransactionRunnerImpl(TenantAwareDataSource(ds("config_platform", "config_platform_pw")), tenants)
    val purgeTx = TransactionRunnerImpl(TenantAwareDataSource(ds("config_purge", "config_purge_pw")), tenants)
    val admin: DataSource = ds(container.username, container.password)

    /** Fails the next audit write when set. */
    @Volatile var failAudit = false

    fun audit(connections: CurrentConnection): AuditWriter = object : AuditWriter {
        override fun record(
            entry: AuditEntry,
            context: EventContext,
        ): Envelope {
            if (failAudit) throw AuditWriteFailed(IllegalStateException("test"))
            return insert(connections, "audit", AUDIT, context, auditJson(entry))
        }
    }

    fun outbox(connections: CurrentConnection): OutboxWriter = object : OutboxWriter {
        override fun append(
            events: List<OutboundEvent>,
            context: EventContext,
        ): List<Envelope> = events.map { insert(connections, "event", it.type, context, it.dataJson) }
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
        val runner = if (platform) platformTx else app
        return JdbcPolicyWriter(
            connections = runner,
            catalogue = catalogue,
            form = form,
            schema = SCHEMA,
            audit = audit(runner),
            outbox = if (withOutbox) outbox(runner) else null,
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

    fun purge() = JdbcConfigPurge(purgeTx, SCHEMA)

    fun <T> inTenant(
        tenant: TenantId,
        block: () -> T,
    ): T = tenants.bind(tenant) { app.inTransaction(block) }

    fun <T> inPlatform(block: () -> T): T = tenants.bindPlatform { platformTx.inTransaction(block) }

    fun count(sql: String): Long = admin.connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use {
                it.next()
                it.getLong(1)
            }
        }
    }

    fun events(tenant: TenantId): List<StoredEvent> = admin.connection.use { c ->
        c.prepareStatement(
            "select kind, type, tenant_id, entity_ref, data::text from $SCHEMA.test_event " +
                "where tenant_id = ? order by seq",
        )
            .use { st ->
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
        connections.require().prepareStatement(
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
    ): DataSource = PGSimpleDataSource().apply {
        setUrl(container.jdbcUrl)
        this.user = user
        this.password = password
    }

    companion object {
        const val SCHEMA = "svc"
        const val AUDIT = "com.dynokore.gst.platform.AuditEntryRecorded.v1"

        /** 2026-10-01 08:00 in Singapore. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
        val CORRELATION = CorrelationId("corr-1")
        val CAUSATION = CausationId("cause-1")

        fun clockAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
    }
}
