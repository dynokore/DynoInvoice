package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.KillableTransactionRunner
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.TestCatalogue
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * One PostgreSQL container (17 or 18) holding a fixture service `submission-svc` (the real service does not
 * exist yet): its outbox from outbox-and-queue, the audit-log `override_grant` table, and two state tables
 * the fake operations of INV-AC-2593 change and read. The roles are outbox-and-queue's fixture roles. All DDL runs as
 * init SQL before the container starts (owner ruling INV-Q-4508 (A), route R1); the outbox refuses inserts once a row
 * is in `outbox_gate` ([refuseOutbox]), a trigger provisioned there in place of a later `revoke`.
 */
class AuditHarness(
    image: String,
) : AutoCloseable {
    val fixture =
        OutboxPostgresFixture(
            image,
            OutboxSchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS) + "\n" +
                AuditSchema.ddl(SCHEMA, OutboxPostgresFixture.APP) +
                """

                create table $SCHEMA.idp_group_mapping (idp_group text primary key, role text not null);
                create table $SCHEMA.support_bundle (id uuid primary key, tenant_id uuid not null);
                grant select, insert, update on $SCHEMA.idp_group_mapping, $SCHEMA.support_bundle
                    to ${OutboxPostgresFixture.APP};
                create table $SCHEMA.outbox_gate (refuse boolean not null);
                create function $SCHEMA.refuse_outbox() returns trigger language plpgsql security definer
                    set search_path = pg_catalog, $SCHEMA as ${'$'}${'$'}
                begin
                    if exists (select 1 from $SCHEMA.outbox_gate where refuse) then
                        raise exception 'the outbox refuses inserts (test gate)' using errcode = '42501';
                    end if;
                    return new;
                end ${'$'}${'$'};
                create trigger outbox_gate before insert on $SCHEMA.outbox
                    for each row execute function $SCHEMA.refuse_outbox();
                """.trimIndent(),
        )
    internal val db = AuditTestDb(fixture)
    val tenants = db.tenants
    val boundary = KillableTransactionRunner(db.appDataSource, tenants)
    val outbox = JdbcOutboxWriter(boundary, SCHEMA, "submission-svc", "submission-svc-1", TestCatalogue.load(), CLOCK)
    val audit = OutboxAuditWriter(outbox)
    val operations = TransactionalAuditedOperations(boundary, audit)

    /** The module's tenancy-jooq DSLContext on the runtime login, tables declared with their class. */
    val dsl: DSLContext =
        db.dsl(
            boundary,
            mapOf(
                "$SCHEMA.override_grant" to TableClass.TENANT,
                "$SCHEMA.idp_group_mapping" to TableClass.PLATFORM_SCOPED,
                "$SCHEMA.support_bundle" to TableClass.TENANT,
            ),
        )
    val overrides = JdbcOverrideService(dsl, boundary, audit, CATALOGUE, SCHEMA, CLOCK)
    private val ownerDsl =
        db.dsl(
            db.ownerTx,
            mapOf(
                "$SCHEMA.idp_group_mapping" to TableClass.PLATFORM_SCOPED,
                "$SCHEMA.outbox_gate" to TableClass.PLATFORM_SCOPED,
            ),
        )

    /** A row of the platform-scoped table [name], written by the schema owner (the runtime login has no grant). */
    private fun ownerInsert(
        name: String,
        vararg values: Any,
    ) {
        tenants.bindPlatform("test-owner") {
            db.ownerTx.inTransaction {
                ownerDsl.insertInto(DSL.table(DSL.name(SCHEMA, name))).values(*values).execute()
            }
        }
    }

    /** A pre-existing mapping row, as an earlier migration would have left it. */
    fun seedMapping(
        group: String,
        role: String,
    ) = ownerInsert("idp_group_mapping", group, role)

    /** From now on the outbox refuses every insert (INV-AC-2593). */
    fun refuseOutbox() = ownerInsert("outbox_gate", true)

    // Runs [block] with [tenant] bound, as an inbound adapter would after verifying the token (ARC-21).
    fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), "test-actor", block)

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

    /** Number of rows of the one-number [sql], summed over the RLS binding of each of [bound]. */
    fun count(
        sql: String,
        bound: Collection<UUID>,
        vararg args: Any,
    ): Long = db.count(sql, bound, *args)

    fun auditEntries(
        bound: Collection<UUID>,
        subjectLike: String = "%",
    ): Long =
        count(
            "select count(*) from $SCHEMA.outbox where type = ? and subject like ?",
            bound,
            OutboxAuditWriter.EVENT_TYPE,
            subjectLike,
        )

    /** Whether [code] is overridden for [version] of [doc], asked inside a transaction of [tenant]. */
    fun overridden(
        tenant: UUID,
        doc: UUID,
        version: Long,
        code: String = MDM_003,
    ): Boolean =
        asTenant(tenant) {
            boundary.inTransaction { overrides.isOverridden(DocUuid(doc), version, code) }
        }

    override fun close() = db.close()

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
