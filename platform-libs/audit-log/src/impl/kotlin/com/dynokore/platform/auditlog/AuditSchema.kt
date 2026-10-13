package com.dynokore.platform.auditlog

import com.dynokore.platform.tenancykernel.SqlIdentifier
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
 * entries, which [LocalAuditWriter] writes, and [purgeDdl] for the audited purge of those entries.
 *
 * Identifiers enter the SQL text only through tenancy-kernel's [SqlIdentifier.require] (INV-REQ-854 b5, coordinator
 * ruling INV-Q-4036); tenancy-kernel's RLS trailer is joined as a separate statement group, never spliced in.
 */
object AuditSchema {
    const val OVERRIDE_GRANT = "override_grant"
    const val AUDIT_ENTRY = "audit_entry"

    /**
     * `purge_audit_entries(cut_off, reason, entry_id, source, subject, instance_id, correlation_id, causation_id)`:
     * the audited purge of [AUDIT_ENTRY] ([purgeDdl]); returns the number of entries deleted.
     */
    const val PURGE_AUDIT_ENTRIES = "purge_audit_entries"

    /** The action of the purge's own audit entry (INV-API-3822). */
    const val PURGE_ACTION = "purgeAuditEntries"

    /** The legal minimum retention of an audit entry, enforced in SQL (INV-REQ-1304, INV-REQ-1378, INV-Q-3028). */
    const val LEGAL_FLOOR = "5 years"

    /** The reserved platform tenant and entity (`TenantId.PLATFORM`, `EntityRef.PLATFORM`; INV-Q-2927, INV-Q-3012). */
    internal const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"

    /** The argument types of [PURGE_AUDIT_ENTRIES], for its hand-over and grants. */
    private const val PURGE_ARGUMENTS = "timestamptz, text, uuid, text, text, text, text, text"

    fun ddl(
        schema: String,
        appRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val role = SqlIdentifier.require(appRole, "role")
        val table =
            """
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
            """.trimIndent()
        val rls = TenantRlsMigration.ddl(schema, OVERRIDE_GRANT, policyName = "${OVERRIDE_GRANT}_tenant_isolation")
        return listOf(table, rls, "grant select, insert on $s.$OVERRIDE_GRANT to $role;").joinToString("\n")
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
     *   automatic expiry. A row is deleted only through the audited purge function of [purgeDdl].
     * - Classification (CS-56): `actor` is personal data (operator staff); `reason` and `prior_state` are content.
     */
    fun auditEntryDdl(
        schema: String,
        appRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val role = SqlIdentifier.require(appRole, "role")
        val table =
            """
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
            """.trimIndent()
        val rls = TenantRlsMigration.ddl(schema, AUDIT_ENTRY, policyName = "${AUDIT_ENTRY}_tenant_isolation")
        return listOf(table, rls, "grant select, insert on $s.$AUDIT_ENTRY to $role;").joinToString("\n")
    }

    /**
     * The audited purge of [auditEntryDdl]'s table, for the service's own additive migration after it (INV-T-3984,
     * used by control-plane-svc's purgeAuditEntries, INV-API-3822, INV-T-3099). Run as `<svc>_migrate`, which must be
     * a member of [sysRole].
     *
     * Creates [PURGE_AUDIT_ENTRIES], a SECURITY DEFINER function owned by the NOLOGIN [sysRole] (owner ruling
     * INV-Q-3953, INV-ADR-3968 item 6): static SQL, `SET search_path = pg_catalog, <schema>, pg_temp` (coordinator
     * ruling INV-Q-4038), EXECUTE revoked from PUBLIC and granted to [runtimeRole] only. [runtimeRole] keeps
     * `SELECT` and `INSERT` on the table and nothing more (INV-T-3001); [sysRole] gets `SELECT`, `INSERT` and
     * `DELETE`, its `SELECT` and `DELETE` policies admit only rows older than [LEGAL_FLOOR], and a restrictive
     * `DELETE` policy holds every delete of [sysRole] to that age, whatever tenant is bound.
     *
     * The caller binds the reserved platform tenant and its actor (`PlatformTenantBinding.bindPlatform(actor)`, which
     * sets `app.actor`), stages the purge's entry through [AuditStager.stage] and passes its envelope fields
     * (coordinator ruling INV-Q-4277, the pattern of INV-Q-4254 A). In one transaction the function then:
     * 1. refuses (SQLSTATE 42501) when `app.actor` is unset or the bound tenant is not the platform tenant;
     * 2. refuses (SQLSTATE 22023) a cut-off later than now minus [LEGAL_FLOOR], a blank reason, subject, instance,
     *    correlation or causation, a source other than `/gst/<service>`, and an entry id that is not a version-7 UUID
     *    or is already present;
     * 3. writes the purge's own entry first, under the platform tenant and entity, recorded at the database's
     *    `clock_timestamp()` (no caller passes a time, coordinator ruling INV-Q-4316), with the actor from `app.actor`,
     *    action [PURGE_ACTION], scope `platform (<service> audit_entry)`, the caller's reason, and as prior state
     *    the cut-off, the row count and the oldest and newest `recorded_at` of the rows to be deleted (no prior
     *    state when there are none, INV-REQ-2588 b6);
     * 4. deletes the entries recorded before the cut-off and returns their number.
     * A refused check raises, so nothing is written or deleted. The computed floor (the longest tenant VP-43
     * retention) stays the caller's check before the call (INV-API-3822).
     *
     * @param service the service name, `/gst/<service>` being the only accepted source (for example
     *   `control-plane-svc`).
     */
    fun purgeDdl(
        schema: String,
        runtimeRole: String,
        sysRole: String,
        service: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        require(
            SERVICE.matches(service),
        ) { "service must be a lower-case name like 'control-plane-svc', was '$service'" }
        // The service name has hyphens, so it enters the SQL text in its identifier form and is restored in SQL.
        val svc = SqlIdentifier.require(service.replace('-', '_'), "service")
        return """
            grant usage on schema $s to $sys;
            grant select, insert, delete on $s.$AUDIT_ENTRY to $sys;
            create policy ${AUDIT_ENTRY}_sys_select on $s.$AUDIT_ENTRY for select to $sys
                using (recorded_at < now() - interval '$LEGAL_FLOOR');
            create policy ${AUDIT_ENTRY}_sys_purge on $s.$AUDIT_ENTRY for delete to $sys
                using (recorded_at < now() - interval '$LEGAL_FLOOR');
            create policy ${AUDIT_ENTRY}_sys_purge_floor on $s.$AUDIT_ENTRY as restrictive for delete to $sys
                using (recorded_at < now() - interval '$LEGAL_FLOOR');
            create function $s.$PURGE_AUDIT_ENTRIES(p_cut_off timestamptz, p_reason text, p_entry_id uuid,
                p_source text, p_subject text, p_instance_id text, p_correlation_id text, p_causation_id text)
                returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                v_service constant text := replace('$svc', '_', '-');
                v_actor text := nullif(btrim(current_setting('app.actor', true)), '');
                v_rows bigint;
                v_oldest timestamptz;
                v_newest timestamptz;
                v_deleted bigint;
            begin
                if v_actor is null then
                    raise exception 'app.actor is not set: the purge records its actor' using errcode = '42501';
                end if;
                if current_setting('app.tenant_id', true) is distinct from '$PLATFORM' then
                    raise exception 'the purge runs under the platform tenant only' using errcode = '42501';
                end if;
                if p_cut_off is null or p_cut_off > now() - interval '$LEGAL_FLOOR' then
                    raise exception 'cut-off % is later than the legal floor of $LEGAL_FLOOR', p_cut_off
                        using errcode = '22023';
                end if;
                if p_reason is null or btrim(p_reason) = '' then
                    raise exception 'the purge needs a reason' using errcode = '22023';
                end if;
                if p_subject is null or btrim(p_subject) = '' or p_instance_id is null or btrim(p_instance_id) = ''
                    or p_correlation_id is null or btrim(p_correlation_id) = ''
                    or p_causation_id is null or btrim(p_causation_id) = '' then
                    raise exception 'the staged entry needs a subject, instance, correlation and causation'
                        using errcode = '22023';
                end if;
                if p_source is distinct from ('/gst/' || v_service) then
                    raise exception 'source % is not /gst/%', p_source, v_service using errcode = '22023';
                end if;
                if p_entry_id is null
                    or p_entry_id::text !~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' then
                    raise exception 'entry id % is not a version-7 UUID', p_entry_id using errcode = '22023';
                end if;
                if exists (select 1 from $s.$AUDIT_ENTRY where tenant_id = '$PLATFORM'::uuid and id = p_entry_id) then
                    raise exception 'entry id % is already present', p_entry_id using errcode = '22023';
                end if;
                select count(*), min(recorded_at), max(recorded_at) into v_rows, v_oldest, v_newest
                from $s.$AUDIT_ENTRY where recorded_at < p_cut_off;
                insert into $s.$AUDIT_ENTRY
                    (id, tenant_id, entity_ref, recorded_at, source, subject, doc_uuid, transaction_date,
                     instance_id, correlation_id, causation_id, actor, action, scope, reason, prior_state)
                values (p_entry_id, '$PLATFORM'::uuid, '$PLATFORM', clock_timestamp(), p_source, p_subject, null, null,
                    p_instance_id, p_correlation_id, p_causation_id, v_actor, '$PURGE_ACTION',
                    'platform (' || v_service || ' audit_entry)', p_reason,
                    case when v_rows = 0 then null else jsonb_build_object('cutOff', p_cut_off, 'rowCount', v_rows,
                        'oldest', v_oldest, 'newest', v_newest) end);
                delete from $s.$AUDIT_ENTRY where recorded_at < p_cut_off;
                get diagnostics v_deleted = row_count;
                return v_deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$PURGE_AUDIT_ENTRIES($PURGE_ARGUMENTS) owner to $sys;
            revoke all on function $s.$PURGE_AUDIT_ENTRIES($PURGE_ARGUMENTS) from public;
            grant execute on function $s.$PURGE_AUDIT_ENTRIES($PURGE_ARGUMENTS) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private val SERVICE = Regex("^[a-z][a-z-]*$")
}
