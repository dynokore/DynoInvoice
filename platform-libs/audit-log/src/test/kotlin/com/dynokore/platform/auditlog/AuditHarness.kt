package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.KillableTransactionRunner
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.TestCatalogue
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * One PostgreSQL container (17 or 18) holding a fixture service `submission-svc` (the real service does not
 * exist yet): its outbox from outbox-and-queue, the audit-log `override_grant` table, and two state tables
 * the fake operations of INV-AC-2593 change and read. The roles are outbox-and-queue's fixture roles.
 */
class AuditHarness(image: String) : AutoCloseable {
    val fixture = OutboxPostgresFixture(image)
    val tenants = TenantContextImpl()
    val boundary = KillableTransactionRunner(fixture.appDataSource(), tenants)
    val outbox = JdbcOutboxWriter(boundary, SCHEMA, "submission-svc", "submission-svc-1", TestCatalogue.load(), CLOCK)
    val audit = OutboxAuditWriter(outbox)
    val operations = TransactionalAuditedOperations(boundary, audit)
    val overrides = JdbcOverrideService(boundary, boundary, audit, CATALOGUE, SCHEMA, CLOCK)

    init {
        fixture.createService(
            SCHEMA,
            AuditSchema.ddl(SCHEMA, OutboxPostgresFixture.APP) +
                """
                create table $SCHEMA.idp_group_mapping (idp_group text primary key, role text not null);
                create table $SCHEMA.support_bundle (id uuid primary key, tenant_id uuid not null);
                grant select, insert, update on $SCHEMA.idp_group_mapping, $SCHEMA.support_bundle
                    to ${OutboxPostgresFixture.APP};
                """.trimIndent(),
        )
    }

    /** Runs [block] with [tenant] bound, as an inbound adapter would after verifying the token (ARC-21). */
    fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), block)

    fun context(
        tenant: UUID,
        doc: UUID? = null,
        key: String = doc?.toString() ?: "tenant-$tenant",
    ) = EventContext(
        tenant = TenantId(tenant),
        entity = EntityRef("entity-1"),
        docUuid = doc?.let { DocUuid(it) },
        transactionDate = LocalDate.of(2026, 9, 30),
        aggregateKey = key,
        aggregateVersion = null,
        correlation = CorrelationId("corr-$key"),
        causation = CausationId("cmd-1"),
    )

    fun overrideCommand(
        tenant: UUID,
        doc: UUID,
        version: Long = 1,
        code: String = MDM_003,
        reason: String? = "UEN confirmed with the supplier",
        roles: Set<String> = setOf("tax.approver"),
    ) = OverrideCommand(
        tenant = TenantId(tenant),
        entity = EntityRef("entity-1"),
        docUuid = DocUuid(doc),
        documentVersion = version,
        transactionDate = LocalDate.of(2026, 9, 30),
        code = code,
        reason = reason,
        actor = "user-7",
        actorRoles = roles,
        priorStateJson = """{"status":"BLOCKED","code":"$code"}""",
        correlation = CorrelationId("corr-$doc"),
        causation = CausationId("cmd-$doc"),
    )

    /** Number of rows (all tenants, as the superuser) for [sql]. */
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

    fun auditEntries(subjectLike: String = "%"): Long = count(
        "select count(*) from $SCHEMA.outbox where type = ? and subject like ?",
        OutboxAuditWriter.EVENT_TYPE,
        subjectLike,
    )

    /** Whether [code] is overridden for [version] of [doc], asked inside a transaction of [tenant]. */
    fun overridden(
        tenant: UUID,
        doc: UUID,
        version: Long,
        code: String = MDM_003,
    ): Boolean = asTenant(tenant) {
        boundary.inTransaction { overrides.isOverridden(DocUuid(doc), version, code) }
    }

    override fun close() {
        fixture.close()
    }

    companion object {
        const val SCHEMA = "submission"
        const val MDM_003 = "MDM-003"
        const val SUB_009 = "SUB-009"
        const val PAY_001 = "PAY-001"
        val IMAGES = listOf("postgres:17", "postgres:18")
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
        val CATALOGUE =
            ErrorCatalogue {
                mapOf(
                    MDM_003 to ErrorDisposition.OVERRIDABLE,
                    SUB_009 to ErrorDisposition.TERMINAL,
                    PAY_001 to ErrorDisposition.MANUAL,
                )[it]
            }
    }
}
