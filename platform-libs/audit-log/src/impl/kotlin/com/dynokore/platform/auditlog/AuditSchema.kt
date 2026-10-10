package com.dynokore.platform.auditlog

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the one table this module owns in a service's own schema: `override_grant`, one row per
 * override, scoped to a document version (INV-REQ-1180). It holds the tenant's data, so it carries the
 * mandatory `tenant_id` and tenancy-kernel's forced row-level security (CS-19). The app role may
 * `SELECT` and `INSERT` only: a grant is never updated or deleted (immutable, INV-REQ-1376). The audit
 * entries themselves live in the outbox ([com.dynokore.platform.outboxandqueue.OutboxSchema]) and in
 * `evidence-svc`. A service's migration (TPL-12) embeds [ddl].
 */
object AuditSchema {
    const val OVERRIDE_GRANT = "override_grant"

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

    private fun identifier(
        name: String,
        what: String,
    ): String {
        require(Regex("^[a-z][a-z0-9_]{0,62}$").matches(name)) { "$what must be a plain lower-case identifier, was '$name'" }
        return name
    }
}
