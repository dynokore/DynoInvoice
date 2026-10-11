package com.dynokore.platform.auditlog

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the one table this module owns in a service's own schema: `override_grant`, one row per
 * override, scoped to a document version (INV-REQ-1180). It holds the tenant's data, so it carries the
 * mandatory `tenant_id` and tenancy-kernel's forced row-level security (CS-19). The app role may
 * `SELECT` and `INSERT` only: a grant is never updated or deleted (immutable, INV-REQ-1376). The audit
 * entries themselves live in the outbox ([com.dynokore.platform.outboxandqueue.OutboxSchema]) and in
 * `evidence-svc`. A service's migration (TPL-12) embeds [ddl].
 *
 * A service without an outbox (`control-plane-svc`, INV-Q-2958) embeds [auditEntryDdl] instead for its own
 * entries, which [LocalAuditWriter] writes.
 */
object AuditSchema {
    const val OVERRIDE_GRANT = "override_grant"
    const val AUDIT_ENTRY = "audit_entry"

    fun ddl(
        schema: String,
        appRole: String,
    ): String {
        val s = identifier(schema, "schema")
        val role = identifier(appRole, "role")
        val rls = TenantRlsMigration.ddl(s, OVERRIDE_GRANT, policyName = "${OVERRIDE_GRANT}_tenant_isolation")
        return """
            create table $s.$OVERRIDE_GRANT (
                id uuid primary key,
                tenant_id uuid not null,
                doc_uuid uuid not null,
                document_version bigint not null check (document_version >= 1),
                error_code text not null,
                actor text not null,
                reason text not null check (btrim(reason) <> ''),
                granted_at timestamptz not null,
                audit_event_id text not null,
                unique (tenant_id, doc_uuid, document_version, error_code)
            );
            comment on column $s.$OVERRIDE_GRANT.reason is 'class: content';
            $rls
            grant select, insert on $s.$OVERRIDE_GRANT to $role;
        """.trimIndent()
    }

    /**
     * The local audit table of a service without an outbox (INV-Q-2958; `control-plane-svc`, schema
     * `controlplane`), written by [LocalAuditWriter] in the command's transaction (INV-REQ-1226). Additive:
     * one new table and its index, nothing existing altered.
     *
     * - Tenant: mandatory `tenant_id` from the entry's `EventContext`; an operator action with no tenant is
     *   stored under the reserved `TenantId.PLATFORM` (INV-Q-2927 option D). `tenant_id` leads every index,
     *   and tenancy-kernel's forced row-level security applies (CS-19).
     * - Immutability: [appRole] may `SELECT` and `INSERT` only, never `UPDATE` or `DELETE`, so the trail
     *   cannot be edited (INV-REQ-2588, INV-REQ-1376).
     * - Retention (owner ruling INV-Q-3028): kept at least as long as the longest evidence retention any tenant
     *   of the installation has set under VP-43 (INV-REQ-1378), never less than 5 years from the action; no
     *   automatic expiry. A row is deleted only through a future audited operation (a dated purge that itself
     *   writes an audit entry), tracked as its own Keel task; this module offers no purge API.
     * - Classification (CS-56): `actor` is personal data (operator staff); `reason` and `prior_state` are content.
     */
    fun auditEntryDdl(
        schema: String,
        appRole: String,
    ): String {
        val s = identifier(schema, "schema")
        val role = identifier(appRole, "role")
        val rls = TenantRlsMigration.ddl(s, AUDIT_ENTRY, policyName = "${AUDIT_ENTRY}_tenant_isolation")
        return """
            create table $s.$AUDIT_ENTRY (
                id uuid not null,
                tenant_id uuid not null,
                entity_ref text not null,
                recorded_at timestamptz not null,
                source text not null,
                subject text not null,
                doc_uuid uuid,
                transaction_date date,
                instance_id text not null,
                correlation_id text not null,
                causation_id text not null,
                actor text not null check (btrim(actor) <> ''),
                action text not null check (btrim(action) <> ''),
                scope text not null check (btrim(scope) <> ''),
                reason text check (reason is null or btrim(reason) <> ''),
                prior_state jsonb check (prior_state is null or jsonb_typeof(prior_state) = 'object'),
                primary key (tenant_id, id)
            );
            create index ${AUDIT_ENTRY}_tenant_time on $s.$AUDIT_ENTRY (tenant_id, recorded_at);
            comment on table $s.$AUDIT_ENTRY is
                'retention: longest tenant VP-43 period, min 5 years; no expiry; audited purge only (INV-Q-3028)';
            comment on column $s.$AUDIT_ENTRY.actor is 'class: personal';
            comment on column $s.$AUDIT_ENTRY.reason is 'class: content';
            comment on column $s.$AUDIT_ENTRY.prior_state is 'class: content';
            $rls
            grant select, insert on $s.$AUDIT_ENTRY to $role;
        """.trimIndent()
    }

    internal fun identifier(
        name: String,
        what: String,
    ): String {
        require(Regex("^[a-z][a-z0-9_]{0,62}$").matches(name)) { "$what must be a plain lower-case identifier, was '$name'" }
        return name
    }
}
