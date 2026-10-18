package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the tables this module owns in a service's own schema, and the SECURITY DEFINER functions that are the only
 * way to write an installation value or purge a tenant's rows. A service's Flyway migration, run as `<svc>_migrate`
 * (a member of `<svc>_sys`), embeds [ddl] (TPL-12). One runtime login per service (INV-REQ-1189 b2, INV-ADR-3968;
 * owner rulings INV-Q-3931, INV-Q-3953): 2.0.0 replaces 1.x's application, platform and purge roles with the runtime
 * login and the NOLOGIN `<svc>_sys`, and has no upgrade path from 1.x (coordinator ruling INV-Q-4068).
 *
 *  - `config_setting`: values set for a tenant, group, entity, feed, Access Point connection or client entity, as
 *    an override or as the value an operating profile applied (source `PROFILE` with the profile id and version,
 *    ruling INV-Q-2979), and `relaxation_ref`, the relaxation a LOOSER value consumed (unique, INV-Q-3138). Tenant
 *    data: `tenant_id` leads every index, row-level security is enabled AND forced. The runtime login may select and
 *    insert only, so a superseded value stays as history (INV-Q-2938).
 *  - `config_installation_setting`: platform-wide values. They hold no tenant's data, so no `tenant_id` and no RLS
 *    (INV-T-2869): every tenant reads them. The runtime login may only select; a value is written only by
 *    [WRITE_INSTALLATION_SETTING], with its audit entry and, in a service with an outbox, its `PolicyChanged`.
 *  - `config_resolved_value`: the resolved value and its source, recorded on one document version's record
 *    (INV-REQ-1068 behaviour #2), with `capped_by`, the caps it carried (INV-Q-3206). Insert-only, first write wins.
 *  - `config_relaxation_acknowledgement`: one row per acknowledger of a control relaxation (INV-Q-3125, INV-Q-3138).
 *    Tenant data as `config_setting`.
 *
 * Tenant rows are deleted only with their subject or their tenant, through [PURGE_SUBJECT] and [PURGE_TENANT]
 * (INV-Q-2938). Every function is owned by `<svc>_sys`, has static SQL and `SET search_path = pg_catalog, <schema>,
 * pg_temp` (coordinator ruling INV-Q-4038), and has EXECUTE revoked from PUBLIC and granted to the runtime login only.
 * Identifiers enter SQL text only through tenancy-kernel's [SqlIdentifier.require] (INV-REQ-854 b5, coordinator ruling
 * INV-Q-4036); statement groups are joined outside SQL text.
 */
object ConfigSchema {
    const val SETTING = "config_setting"
    const val INSTALLATION_SETTING = "config_installation_setting"
    const val RESOLVED_VALUE = "config_resolved_value"
    const val RELAXATION_ACKNOWLEDGEMENT = "config_relaxation_acknowledgement"

    /**
     * `write_installation_setting(vp_key, option_keys, option_values, effective_from, reason, audit, policy_changed)`:
     * an installation value with its audit entry and `PolicyChanged`, atomically; returns `(setting_id, stored_at)`.
     */
    const val WRITE_INSTALLATION_SETTING = "write_installation_setting"

    /** `config_purge_subject(subject_type, subject_ref)`: the bound tenant's resolved values of one subject. */
    const val PURGE_SUBJECT = "config_purge_subject"

    /**
     * `config_purge_tenant(tenant)`: every resolved value, setting and acknowledgement of the bound tenant; returns
     * `(resolved_values, settings, relaxation_acknowledgements)`.
     */
    const val PURGE_TENANT = "config_purge_tenant"

    /**
     * `config_option_value_maximum(vp_key, option_key)`: the largest numeric member of the value stored for the option
     * over every tenant and scope, as a number only; refuses unless the bound tenant is the platform tenant
     * (INV-T-6538, INV-Q-6529 A, INV-Q-6527 A). A service embeds it by a Flyway migration of its own.
     */
    const val OPTION_VALUE_MAXIMUM = "config_option_value_maximum"

    /** `config_installation_mode()`: the [EventMode] [ddl] fixed, read by [WRITE_INSTALLATION_SETTING]. */
    const val INSTALLATION_MODE = "config_installation_mode"

    /** The reserved platform tenant and entity (`TenantId.PLATFORM`, `EntityRef.PLATFORM`; INV-Q-2927, INV-Q-3012). */
    internal const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"

    /** The audit event type (API-40) and its topic; the type's schema is owned by `service-runtime`. */
    internal const val AUDIT_ENTRY_RECORDED = "com.dynokore.gst.platform.AuditEntryRecorded.v1"
    internal const val AUDIT_TOPIC = "gst.platform.audit-entry-recorded.v1"

    /** The `PolicyChanged` event type (API-40, schema owned by `service-runtime`) and its topic (INV-API-3306). */
    internal const val POLICY_CHANGED = "com.dynokore.gst.platform.PolicyChanged.v1"
    internal const val POLICY_CHANGED_TOPIC = "gst.platform.policy-changed.v1"

    /** The audit action of a value written by `setPolicy`. */
    internal const val SET_POLICY = "setPolicy"

    /** The scope text of an installation value (`SettingTarget.scopeText`). */
    internal const val INSTALLATION_SCOPE = "INSTALLATION"

    /** outbox-and-queue's `OutboxSchema.OUTBOX` and audit-log's `AuditSchema.AUDIT_ENTRY`, in the same schema. */
    private const val OUTBOX = "outbox"
    private const val AUDIT_ENTRY = "audit_entry"

    private const val WRITE_ARGUMENTS = "text, text[], jsonb, date, text, jsonb, jsonb"

    private const val VP_KEY = "check (vp_key ~ '^VP-[0-9]{2,3}$')"
    private const val OPTION_KEYS =
        "check (cardinality(option_keys) >= 1 and array_position(option_keys, null) is null)"
    private const val LEVELS = "'TENANT', 'GROUP', 'ENTITY', 'FEED', 'AP_CONNECTION', 'CLIENT_ENTITY'"
    private const val OPTION_VALUES = "check (option_values is null or jsonb_typeof(option_values) = 'object')"

    /**
     * Where [WRITE_INSTALLATION_SETTING] writes the rows that go with a value, fixed by [ddl] and never by an argument
     * (coordinator ruling INV-Q-4254 A; INV-Q-3142).
     */
    enum class EventMode {
        /** The audit entry and `PolicyChanged` as two rows of the service's outbox (outbox-and-queue's `OutboxSchema`). */
        OUTBOX,

        /**
         * A service without an outbox (`control-plane-svc`, INV-Q-2958): the audit entry in the local `audit_entry`
         * (audit-log's `AuditSchema.auditEntryDdl` and `purgeDdl`, which grants `<svc>_sys` its INSERT), no
         * `PolicyChanged`.
         */
        NO_OUTBOX,
    }

    /**
     * Every table and function of the current version, for a service that has none of them yet. Run as
     * `<svc>_migrate`, a member of [sysRole], after the DDL of the tables [mode] writes into: outbox-and-queue's
     * `OutboxSchema.ddl` ([EventMode.OUTBOX]), or audit-log's `AuditSchema.auditEntryDdl` and `purgeDdl`
     * ([EventMode.NO_OUTBOX]).
     *
     * Precondition (coordinator rulings INV-Q-6586, INV-Q-6663): this DDL does not create `<schema>.bound_tenant()` and
     * `<schema>.bound_actor()`, runs no role switch and never runs tenancy-kernel's `BindingKeyMigration.ddl`, which
     * runs only as `<svc>_sys`. The schema must already hold both functions, created once per schema as `<svc>_sys`
     * before this DDL (who runs it in an installation is INV-Q-6580). On a schema without them the DDL applies and
     * every function below raises (SQLSTATE 42883) and changes nothing.
     *
     * [runtimeRole] (`<svc>_runtime`) gets `SELECT` and `INSERT` on the tenant tables, `SELECT` on
     * `config_installation_setting` and EXECUTE on the functions; [sysRole] (`<svc>_sys`) owns the functions and holds
     * `INSERT` on `config_installation_setting` and `DELETE` on the tenant tables, under their tenant policy.
     *
     * [WRITE_INSTALLATION_SETTING] (INV-ADR-3968 item 6, owner ruling INV-Q-3931, coordinator rulings INV-Q-4254 A and
     * INV-Q-4316) takes the value and the rows Kotlin staged through their owning modules (audit-log's `AuditStager`,
     * outbox-and-queue's `OutboxWriter.stage`), each as a JSON object of its outbox columns (`id`, `type`, `source`,
     * `subject`, `tenant_id`, `entity_ref`, `instance_id`, `correlation_id`, `causation_id`, `data`, and `topic` for
     * `PolicyChanged`). Before any insert it refuses, with nothing written:
     * - SQLSTATE 42501: an unbound actor; a bound tenant other than the platform tenant; an audit actor other than
     *   the bound actor;
     * - SQLSTATE 22023: a missing value argument, option values naming an unselected option, a row of another shape;
     *   a tenant or entity other than the platform's; a type or topic other than `AuditEntryRecorded` and
     *   `PolicyChanged` on their topics; an id that is not a version-7 UUID; a source not `/gst/<service>`; a subject
     *   other than `config:<vpId>:INSTALLATION`; a blank instance, correlation or causation, or rows that disagree on
     *   them; an audit entry without a prior state object (INV-REQ-2588 b2, INV-AC-2229), an audit action other than
     *   `setPolicy`, scope other than `INSTALLATION`, or reason other than the value's;
     *   a `PolicyChanged` payload whose scope, vpId, value, effectiveDate or source differ from the value, or that has
     *   other fields; a `PolicyChanged` row missing in [EventMode.OUTBOX] or given in [EventMode.NO_OUTBOX].
     * Then it inserts the value (id `gen_random_uuid()`, `set_by` the actor), the audit entry and the `PolicyChanged`,
     * every row at one `clock_timestamp()` (a staged time is never taken), the doc fields and `docsequence` null.
     *
     * [PURGE_SUBJECT] and [PURGE_TENANT] (INV-Q-2938) refuse an unbound actor, an unbound or platform tenant, and
     * (purge of a tenant) a tenant other than the bound one (SQLSTATE 42501); the application checks the operation
     * before it calls them.
     */
    fun ddl(
        schema: String,
        runtimeRole: String,
        sysRole: String,
        mode: EventMode = EventMode.OUTBOX,
    ): String {
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        return listOf(
            tables(schema),
            listOf(SETTING, RESOLVED_VALUE, RELAXATION_ACKNOWLEDGEMENT).joinToString("\n") {
                TenantRlsMigration.ddl(schema, it, policyName = "${it}_tenant_isolation")
            },
            grants(schema, runtimeRole, sysRole),
            when (mode) {
                EventMode.OUTBOX -> outboxMode(schema, sysRole)
                EventMode.NO_OUTBOX -> noOutboxMode(schema, sysRole)
            },
            writeInstallationSetting(schema, runtimeRole, sysRole),
            purgeSubject(schema, runtimeRole, sysRole),
            purgeTenant(schema, runtimeRole, sysRole),
            optionValueMaximum(schema, runtimeRole, sysRole),
        ).joinToString("\n")
    }

    /**
     * The tables of [schema] this module declares `PLATFORM_SCOPED` (INV-ADR-3968 item 4a, INV-T-4134):
     * [INSTALLATION_SETTING], whose DDL carries the table comment `platform-scoped: no tenant data`. A service's
     * composition root merges it with its own declaration (`inv.tenancy.platform-scoped-tables`).
     */
    fun platformScopedTables(schema: String): List<String> =
        listOf("${SqlIdentifier.require(schema, "schema")}.$INSTALLATION_SETTING")

    private fun tables(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.$SETTING (
                seq bigint generated always as identity primary key,
                id uuid not null unique,
                tenant_id uuid not null,
                entity_id text,
                vp_key text not null $VP_KEY,
                scope_level text not null check (scope_level in ($LEVELS)),
                scope_id text not null check (scope_id !~ '${BlankText.SQL_BLANK}'),
                option_keys text[] not null $OPTION_KEYS,
                option_values jsonb $OPTION_VALUES,
                source text not null check (source in ('OVERRIDE', 'PROFILE')),
                profile_id text,
                profile_version text,
                effective_from date not null,
                set_by text not null check (set_by !~ '${BlankText.SQL_NOT_ACTOR}'),
                reason text not null check (reason !~ '${BlankText.SQL_BLANK}'),
                recorded_at timestamptz not null,
                relaxation_ref uuid,
                check ((scope_level in ('TENANT', 'GROUP')) = (entity_id is null)),
                check ((source = 'PROFILE') = (profile_id is not null and profile_version is not null))
            );
            create index ${SETTING}_lookup on $s.$SETTING
                (tenant_id, vp_key, scope_level, scope_id, source, effective_from desc, seq desc);
            create unique index ${SETTING}_relaxation_ref on $s.$SETTING (tenant_id, relaxation_ref)
                where relaxation_ref is not null;
            comment on column $s.$SETTING.reason is 'class: content';

            create table $s.$INSTALLATION_SETTING (
                seq bigint generated always as identity primary key,
                id uuid not null unique,
                vp_key text not null $VP_KEY,
                option_keys text[] not null $OPTION_KEYS,
                option_values jsonb $OPTION_VALUES,
                effective_from date not null,
                set_by text not null check (set_by !~ '${BlankText.SQL_NOT_ACTOR}'),
                reason text not null check (reason !~ '${BlankText.SQL_BLANK}'),
                recorded_at timestamptz not null
            );
            create index ${INSTALLATION_SETTING}_lookup on $s.$INSTALLATION_SETTING
                (vp_key, effective_from desc, seq desc);
            comment on column $s.$INSTALLATION_SETTING.reason is 'class: content';
            comment on table $s.$INSTALLATION_SETTING is 'platform-scoped: no tenant data';

            create table $s.$RESOLVED_VALUE (
                id uuid primary key,
                tenant_id uuid not null,
                entity_id text,
                subject_type text not null check (subject_type ~ '^[a-z][a-z0-9_]{0,62}$'),
                subject_ref text not null check (subject_ref !~ '${BlankText.SQL_BLANK}'),
                vp_key text not null $VP_KEY,
                transaction_date date not null,
                option_keys text[] not null $OPTION_KEYS,
                option_values jsonb $OPTION_VALUES,
                source text not null check (source in ('OVERRIDE', 'PROFILE', 'DEFAULT')),
                source_level text check (source_level in ('INSTALLATION', $LEVELS)),
                source_scope_id text,
                source_setting_id uuid,
                profile_id text,
                profile_version text,
                resolved_at timestamptz not null,
                capped_by jsonb
                    check (capped_by is null or (jsonb_typeof(capped_by) = 'array' and jsonb_array_length(capped_by) > 0)),
                unique (tenant_id, subject_type, subject_ref, vp_key),
                check ((source = 'DEFAULT') = (source_level is null)),
                check ((source = 'DEFAULT') = (source_scope_id is null)),
                check ((source = 'DEFAULT') = (source_setting_id is null)),
                check ((source = 'PROFILE') = (profile_id is not null and profile_version is not null))
            );

            create table $s.$RELAXATION_ACKNOWLEDGEMENT (
                seq bigint generated always as identity primary key,
                id uuid not null unique,
                relaxation_id uuid not null,
                tenant_id uuid not null,
                entity_id text,
                vp_key text not null $VP_KEY,
                scope_level text not null check (scope_level in ($LEVELS)),
                scope_id text not null check (scope_id !~ '${BlankText.SQL_BLANK}'),
                option_keys text[] not null $OPTION_KEYS,
                option_values jsonb $OPTION_VALUES,
                role text not null check (role !~ '${BlankText.SQL_BLANK}'),
                principal text not null check (principal !~ '${BlankText.SQL_NOT_ACTOR}'),
                effective_from date not null,
                reason text check (reason is null or reason !~ '${BlankText.SQL_BLANK}'),
                acknowledged_at timestamptz not null,
                unique (tenant_id, relaxation_id, role),
                unique (tenant_id, relaxation_id, principal),
                check ((scope_level in ('TENANT', 'GROUP')) = (entity_id is null))
            );
            create index ${RELAXATION_ACKNOWLEDGEMENT}_scope on $s.$RELAXATION_ACKNOWLEDGEMENT
                (tenant_id, vp_key, scope_level, scope_id);
            comment on column $s.$RELAXATION_ACKNOWLEDGEMENT.principal is 'class: personal';
            comment on column $s.$RELAXATION_ACKNOWLEDGEMENT.reason is 'class: content';
            """.trimIndent()
    }

    /**
     * The runtime login works on the tenant tables under their tenant policy only and never writes an installation
     * value directly; the sys role's deletes run under the same policy, so a purge reaches the bound tenant only.
     */
    private fun grants(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            grant usage on schema $s to $runtime, $sys;
            grant select, insert on $s.$SETTING, $s.$RESOLVED_VALUE, $s.$RELAXATION_ACKNOWLEDGEMENT to $runtime;
            grant select on $s.$INSTALLATION_SETTING to $runtime;
            grant insert on $s.$INSTALLATION_SETTING to $sys;
            grant select (tenant_id, subject_type, subject_ref) on $s.$RESOLVED_VALUE to $sys;
            grant select (tenant_id) on $s.$RELAXATION_ACKNOWLEDGEMENT to $sys;
            grant select (tenant_id, vp_key, scope_level, scope_id, source, option_keys, option_values, effective_from, seq)
                on $s.$SETTING to $sys;
            create policy ${SETTING}_sys_select on $s.$SETTING for select to $sys using (true);
            grant delete on $s.$SETTING, $s.$RESOLVED_VALUE, $s.$RELAXATION_ACKNOWLEDGEMENT to $sys;
            """.trimIndent()
    }

    /** [INSTALLATION_MODE] answering [EventMode.OUTBOX]: owned by the sys role, executed by its functions only. */
    private fun outboxMode(
        schema: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$INSTALLATION_MODE() returns text
            language sql immutable set search_path = pg_catalog, $s, pg_temp as $$ select 'OUTBOX' $$;
            grant create on schema $s to $sys;
            alter function $s.$INSTALLATION_MODE() owner to $sys;
            revoke all on function $s.$INSTALLATION_MODE() from public;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /** [INSTALLATION_MODE] answering [EventMode.NO_OUTBOX]: owned by the sys role, executed by its functions only. */
    private fun noOutboxMode(
        schema: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$INSTALLATION_MODE() returns text
            language sql immutable set search_path = pg_catalog, $s, pg_temp as $$ select 'NO_OUTBOX' $$;
            grant create on schema $s to $sys;
            alter function $s.$INSTALLATION_MODE() owner to $sys;
            revoke all on function $s.$INSTALLATION_MODE() from public;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    @Suppress("LongMethod") // one static function body, kept whole so it can be read as the database runs it
    private fun writeInstallationSetting(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$WRITE_INSTALLATION_SETTING(p_vp_key text, p_option_keys text[], p_option_values jsonb,
                p_effective_from date, p_reason text, p_audit jsonb, p_policy_changed jsonb)
                returns table (setting_id uuid, stored_at timestamptz)
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                v_actor text := (select $s.bound_actor());
                v_mode constant text := $s.$INSTALLATION_MODE();
                v_subject text;
                v_value jsonb;
                v_row record;
                v_data jsonb;
                v_id uuid := gen_random_uuid();
                v_now timestamptz;
            begin
                if v_actor is null or v_actor ~ '${BlankText.SQL_NOT_ACTOR}' then
                    raise exception 'the bound actor is not set: an installation value records its actor' using errcode = '42501';
                end if;
                if (select $s.bound_tenant()) is distinct from '$PLATFORM'::uuid then
                    raise exception 'an installation value is written under the platform tenant only' using errcode = '42501';
                end if;
                if p_vp_key is null or p_option_keys is null or p_effective_from is null or p_reason is null
                    or p_reason ~ '${BlankText.SQL_BLANK}' or p_audit is null then
                    raise exception 'the key, options, effective date, reason and audit row are required'
                        using errcode = '22023';
                end if;
                if p_option_values is not null and (jsonb_typeof(p_option_values) <> 'object' or exists (
                    select 1 from jsonb_object_keys(p_option_values) k where not (k = any (p_option_keys)))) then
                    raise exception 'option values name an option that is not selected' using errcode = '22023';
                end if;
                if v_mode = 'OUTBOX' and p_policy_changed is null then
                    raise exception 'this service writes PolicyChanged with every installation value' using errcode = '22023';
                end if;
                if v_mode = 'NO_OUTBOX' and p_policy_changed is not null then
                    raise exception 'this service has no outbox: no PolicyChanged row is written' using errcode = '22023';
                end if;
                v_subject := 'config:' || p_vp_key || ':$INSTALLATION_SCOPE';
                select coalesce(jsonb_agg(case when p_option_values ? o.k
                        then jsonb_build_object('key', o.k, 'value', p_option_values -> o.k)
                        else jsonb_build_object('key', o.k) end order by o.n), '[]'::jsonb)
                    into v_value from unnest(p_option_keys) with ordinality as o(k, n);

                for v_row in
                    select r.kind, r.staged, r.type, r.topic from (values
                        ('audit entry', p_audit, '$AUDIT_ENTRY_RECORDED', null::text),
                        ('PolicyChanged', p_policy_changed, '$POLICY_CHANGED', '$POLICY_CHANGED_TOPIC')
                    ) as r(kind, staged, type, topic) where r.staged is not null
                loop
                    if jsonb_typeof(v_row.staged) <> 'object'
                        or not (v_row.staged ?& array['id', 'type', 'source', 'subject', 'tenant_id', 'entity_ref',
                            'instance_id', 'correlation_id', 'causation_id', 'data'])
                        or (v_row.staged - array['id', 'type', 'source', 'subject', 'tenant_id', 'entity_ref',
                            'instance_id', 'correlation_id', 'causation_id', 'data', 'topic']) <> '{}'::jsonb
                        or (v_row.topic is null and v_row.staged ? 'topic') then
                        raise exception 'the staged % row has another shape', v_row.kind using errcode = '22023';
                    end if;
                    if v_row.staged ->> 'type' is distinct from v_row.type then
                        raise exception 'the staged % row has type %', v_row.kind, v_row.staged ->> 'type'
                            using errcode = '22023';
                    end if;
                    if v_row.topic is not null and v_row.staged ->> 'topic' is distinct from v_row.topic then
                        raise exception 'the staged % row has topic %', v_row.kind, v_row.staged ->> 'topic'
                            using errcode = '22023';
                    end if;
                    if v_row.staged ->> 'tenant_id' is distinct from '$PLATFORM'
                        or v_row.staged ->> 'entity_ref' is distinct from '$PLATFORM' then
                        raise exception 'the staged % row is not under the platform tenant and entity', v_row.kind
                            using errcode = '22023';
                    end if;
                    if jsonb_typeof(v_row.staged -> 'id') is distinct from 'string' or v_row.staged ->> 'id'
                        !~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' then
                        raise exception 'the staged % row id is not a version-7 UUID', v_row.kind using errcode = '22023';
                    end if;
                    if jsonb_typeof(v_row.staged -> 'source') is distinct from 'string'
                        or v_row.staged ->> 'source' !~ '^/gst/[a-z][a-z-]*$' then
                        raise exception 'the staged % row source is not /gst/<service>', v_row.kind using errcode = '22023';
                    end if;
                    if v_row.staged ->> 'subject' is distinct from v_subject then
                        raise exception 'the staged % row subject is not %', v_row.kind, v_subject using errcode = '22023';
                    end if;
                    if jsonb_typeof(v_row.staged -> 'instance_id') is distinct from 'string'
                        or jsonb_typeof(v_row.staged -> 'correlation_id') is distinct from 'string'
                        or jsonb_typeof(v_row.staged -> 'causation_id') is distinct from 'string'
                        or (v_row.staged ->> 'instance_id') ~ '${BlankText.SQL_BLANK}'
                        or (v_row.staged ->> 'correlation_id') ~ '${BlankText.SQL_BLANK}'
                        or (v_row.staged ->> 'causation_id') ~ '${BlankText.SQL_BLANK}' then
                        raise exception 'the staged % row needs an instance, correlation and causation', v_row.kind
                            using errcode = '22023';
                    end if;
                    if jsonb_typeof(v_row.staged -> 'data') is distinct from 'object' then
                        raise exception 'the staged % row data is not an object', v_row.kind using errcode = '22023';
                    end if;
                end loop;
                if p_policy_changed is not null and (p_policy_changed ->> 'source' is distinct from p_audit ->> 'source'
                    or p_policy_changed ->> 'instance_id' is distinct from p_audit ->> 'instance_id'
                    or p_policy_changed ->> 'correlation_id' is distinct from p_audit ->> 'correlation_id'
                    or p_policy_changed ->> 'causation_id' is distinct from p_audit ->> 'causation_id') then
                    raise exception 'the staged rows disagree on source, instance, correlation or causation'
                        using errcode = '22023';
                end if;

                v_data := p_audit -> 'data';
                if jsonb_typeof(v_data -> 'actor') is distinct from 'string' or v_data ->> 'actor' is distinct from v_actor then
                    raise exception 'the audit entry actor is not the bound actor' using errcode = '42501';
                end if;
                if jsonb_typeof(v_data -> 'priorState') is distinct from 'object' then
                    raise exception 'the audit entry has no prior state object (INV-REQ-2588 b2, INV-AC-2229)'
                        using errcode = '22023';
                end if;
                if (v_data - array['actor', 'action', 'scope', 'reason', 'priorState']) <> '{}'::jsonb then
                    raise exception 'the audit entry payload has another shape' using errcode = '22023';
                end if;
                if v_data ->> 'action' is distinct from '$SET_POLICY' then
                    raise exception 'the audit entry action is %', v_data ->> 'action' using errcode = '22023';
                end if;
                if v_data ->> 'scope' is distinct from '$INSTALLATION_SCOPE' then
                    raise exception 'the audit entry scope is %', v_data ->> 'scope' using errcode = '22023';
                end if;
                if v_data ->> 'reason' is distinct from p_reason then
                    raise exception 'the audit entry reason is not the value''s' using errcode = '22023';
                end if;

                if p_policy_changed is not null then
                    v_data := p_policy_changed -> 'data';
                    if v_data -> 'scope' is distinct from to_jsonb('$INSTALLATION_SCOPE'::text) then
                        raise exception 'the PolicyChanged scope is %', v_data -> 'scope' using errcode = '22023';
                    end if;
                    if v_data -> 'vpId' is distinct from to_jsonb(p_vp_key) then
                        raise exception 'the PolicyChanged vpId is %', v_data -> 'vpId' using errcode = '22023';
                    end if;
                    if v_data -> 'value' is distinct from v_value then
                        raise exception 'the PolicyChanged value is not the value written' using errcode = '22023';
                    end if;
                    if v_data -> 'effectiveDate' is distinct from to_jsonb(to_char(p_effective_from, 'YYYY-MM-DD')) then
                        raise exception 'the PolicyChanged effectiveDate is %', v_data -> 'effectiveDate'
                            using errcode = '22023';
                    end if;
                    if v_data is distinct from jsonb_build_object('scope', '$INSTALLATION_SCOPE', 'vpId', p_vp_key,
                        'value', v_value, 'effectiveDate', to_char(p_effective_from, 'YYYY-MM-DD'), 'source', 'OVERRIDE') then
                        raise exception 'the PolicyChanged payload has another source or other fields' using errcode = '22023';
                    end if;
                end if;

                v_now := clock_timestamp();
                insert into $s.$INSTALLATION_SETTING
                    (id, vp_key, option_keys, option_values, effective_from, set_by, reason, recorded_at)
                values (v_id, p_vp_key, p_option_keys, p_option_values, p_effective_from, v_actor, p_reason, v_now);
                if v_mode = 'OUTBOX' then
                    insert into $s.$OUTBOX
                        (id, topic, type, source, subject, event_time, tenant_id, entity_ref, doc_uuid, transaction_date,
                         docsequence, instance_id, correlation_id, causation_id, data)
                    values (p_audit ->> 'id', '$AUDIT_TOPIC', p_audit ->> 'type', p_audit ->> 'source',
                        p_audit ->> 'subject', v_now, '$PLATFORM'::uuid, '$PLATFORM', null, null, null,
                        p_audit ->> 'instance_id', p_audit ->> 'correlation_id', p_audit ->> 'causation_id',
                        p_audit -> 'data');
                    insert into $s.$OUTBOX
                        (id, topic, type, source, subject, event_time, tenant_id, entity_ref, doc_uuid, transaction_date,
                         docsequence, instance_id, correlation_id, causation_id, data)
                    values (p_policy_changed ->> 'id', '$POLICY_CHANGED_TOPIC', p_policy_changed ->> 'type',
                        p_policy_changed ->> 'source', p_policy_changed ->> 'subject', v_now, '$PLATFORM'::uuid,
                        '$PLATFORM', null, null, null, p_policy_changed ->> 'instance_id',
                        p_policy_changed ->> 'correlation_id', p_policy_changed ->> 'causation_id',
                        p_policy_changed -> 'data');
                else
                    insert into $s.$AUDIT_ENTRY
                        (id, tenant_id, entity_ref, recorded_at, source, subject, doc_uuid, transaction_date,
                         instance_id, correlation_id, causation_id, actor, action, scope, reason, prior_state)
                    values ((p_audit ->> 'id')::uuid, '$PLATFORM'::uuid, '$PLATFORM', v_now, p_audit ->> 'source',
                        p_audit ->> 'subject', null, null, p_audit ->> 'instance_id', p_audit ->> 'correlation_id',
                        p_audit ->> 'causation_id', v_actor, p_audit -> 'data' ->> 'action',
                        p_audit -> 'data' ->> 'scope', p_audit -> 'data' ->> 'reason', p_audit -> 'data' -> 'priorState');
                end if;
                return query select v_id, v_now;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$WRITE_INSTALLATION_SETTING($WRITE_ARGUMENTS) owner to $sys;
            revoke all on function $s.$WRITE_INSTALLATION_SETTING($WRITE_ARGUMENTS) from public;
            grant execute on function $s.$WRITE_INSTALLATION_SETTING($WRITE_ARGUMENTS) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private fun purgeSubject(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$PURGE_SUBJECT(p_subject_type text, p_subject_ref text) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                v_actor text := (select $s.bound_actor());
                v_tenant uuid := (select $s.bound_tenant());
                v_deleted bigint;
            begin
                if v_actor is null or v_actor ~ '${BlankText.SQL_NOT_ACTOR}' then
                    raise exception 'the bound actor is not set: a purge records its actor' using errcode = '42501';
                end if;
                if v_tenant is null or v_tenant = '$PLATFORM'::uuid then
                    raise exception 'a purge runs in a transaction of the tenant concerned' using errcode = '42501';
                end if;
                if p_subject_type is null or p_subject_ref is null then
                    raise exception 'the subject is required' using errcode = '22023';
                end if;
                delete from $s.$RESOLVED_VALUE
                where tenant_id = v_tenant and subject_type = p_subject_type and subject_ref = p_subject_ref;
                get diagnostics v_deleted = row_count;
                return v_deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$PURGE_SUBJECT(text, text) owner to $sys;
            revoke all on function $s.$PURGE_SUBJECT(text, text) from public;
            grant execute on function $s.$PURGE_SUBJECT(text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private fun purgeTenant(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$PURGE_TENANT(p_tenant uuid)
                returns table (resolved_values bigint, settings bigint, relaxation_acknowledgements bigint)
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                v_actor text := (select $s.bound_actor());
                v_tenant uuid := (select $s.bound_tenant());
                v_resolved bigint;
                v_settings bigint;
                v_acknowledgements bigint;
            begin
                if v_actor is null or v_actor ~ '${BlankText.SQL_NOT_ACTOR}' then
                    raise exception 'the bound actor is not set: a purge records its actor' using errcode = '42501';
                end if;
                if v_tenant is null or v_tenant = '$PLATFORM'::uuid or p_tenant is distinct from v_tenant then
                    raise exception 'a tenant is purged only in its own transaction' using errcode = '42501';
                end if;
                delete from $s.$RESOLVED_VALUE where tenant_id = v_tenant;
                get diagnostics v_resolved = row_count;
                delete from $s.$SETTING where tenant_id = v_tenant;
                get diagnostics v_settings = row_count;
                delete from $s.$RELAXATION_ACKNOWLEDGEMENT where tenant_id = v_tenant;
                get diagnostics v_acknowledgements = row_count;
                return query select v_resolved, v_settings, v_acknowledgements;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$PURGE_TENANT(uuid) owner to $sys;
            revoke all on function $s.$PURGE_TENANT(uuid) from public;
            grant execute on function $s.$PURGE_TENANT(uuid) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    @Suppress("LongMethod") // one static function body, kept whole so it can be read as the database runs it
    private fun optionValueMaximum(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$OPTION_VALUE_MAXIMUM(p_vp_key text, p_option_key text) returns numeric
            language plpgsql stable security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                v_tenant uuid := (select $s.bound_tenant());
            begin
                if v_tenant is distinct from '$PLATFORM'::uuid then
                    raise exception 'the installation-wide maximum is read in a platform transaction'
                        using errcode = '42501';
                end if;
                if p_vp_key is null or p_vp_key !~ '^VP-[0-9]{2,3}$' then
                    raise exception 'the variation point key is required' using errcode = '22023';
                end if;
                if p_option_key is null or p_option_key ~ '${BlankText.SQL_BLANK}' then
                    raise exception 'the option key is required' using errcode = '22023';
                end if;
                return (
                    with scoped as (
                        select tenant_id, scope_level, scope_id, source, option_keys, option_values, effective_from, seq,
                            bool_or(source = 'OVERRIDE') over (partition by tenant_id, scope_level, scope_id)
                                as has_override
                        from $s.$SETTING
                        where vp_key = p_vp_key
                    ), chosen as (
                        select * from scoped where source = 'OVERRIDE' or not has_override
                    ), ranked as (
                        select *,
                            row_number() over (
                                partition by tenant_id, scope_level, scope_id, effective_from <= current_date
                                order by effective_from desc, seq desc) as rank_in_force,
                            row_number() over (
                                partition by tenant_id, scope_level, scope_id, effective_from
                                order by seq desc) as rank_on_date
                        from chosen
                    ), counted as (
                        select option_values -> p_option_key as member
                        from ranked
                        where p_option_key = any (option_keys)
                            and case when effective_from <= current_date then rank_in_force else rank_on_date end = 1
                    )
                    select max(
                        case jsonb_typeof(member)
                            when 'number' then (member #>> '{}')::numeric
                            when 'object' then (
                                select max((e.value #>> '{}')::numeric)
                                from jsonb_each(member) as e
                                where jsonb_typeof(e.value) = 'number')
                        end)
                    from counted
                );
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$OPTION_VALUE_MAXIMUM(text, text) owner to $sys;
            revoke all on function $s.$OPTION_VALUE_MAXIMUM(text, text) from public;
            grant execute on function $s.$OPTION_VALUE_MAXIMUM(text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }
}
