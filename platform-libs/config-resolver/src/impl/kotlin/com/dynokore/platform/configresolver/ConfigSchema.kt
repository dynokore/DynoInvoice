package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the three tables this module owns in a service's own schema. A service's Flyway migration embeds
 * [ddl] (TPL-12); later versions of this module only ever add to it (CS-19, CS-48).
 *
 *  - `config_setting`: values set for a tenant, group, entity, feed, Access Point connection or client
 *    entity, as an override or as the value an operating profile applied (source `PROFILE` with the profile
 *    id and version, ruling INV-Q-2979). Tenant data: `tenant_id` leads every index, and row-level
 *    security is enabled AND forced. The application role may select and insert only, so a superseded
 *    value stays as history; only the purge role deletes, with its tenant (INV-Q-2938).
 *  - `config_installation_setting`: platform-wide values. They hold no tenant's data, so no `tenant_id`
 *    and no RLS (INV-T-2869 new_table.tenant_key): every tenant reads them. Only the platform role writes,
 *    insert-only (in the private form these are the installation settings of `deployment.operator`,
 *    INV-REQ-1248).
 *  - `config_resolved_value`: the resolved value and its source, recorded on one document version's record
 *    (INV-REQ-1068 behaviour #2). Insert-only, first write wins, one row per (tenant, subject type, subject,
 *    variation point); deleted only with its subject or its tenant, by the purge role (INV-Q-2938).
 *  - 1.1.0 (INV-T-2870): `config_relaxation_acknowledgement`, one row per acknowledger of a control
 *    relaxation, grouped by `relaxation_id` and keyed by scope (INV-Q-3125, INV-Q-3138); and
 *    `config_setting.relaxation_ref`, the relaxation a LOOSER value consumed, unique so a relaxation backs
 *    exactly one setting. Tenant data as `config_setting`: forced RLS, insert-only, deleted only with its
 *    tenant by the purge role. A service already on 1.0.0 runs [upgradeTo110]; a new one runs [ddl], which is
 *    1.0.0's DDL followed by the same upgrade.
 *  - 1.2.0 (INV-T-3176): `config_resolved_value.capped_by`, the directed fields a cross-scope rule or an
 *    installation bound capped at resolution (INV-REQ-1242 behaviour #5, ruling INV-Q-3206). A service on
 *    1.1.0 runs [upgradeTo120]; [ddl] includes it.
 */
object ConfigSchema {
    const val SETTING = "config_setting"
    const val INSTALLATION_SETTING = "config_installation_setting"
    const val RESOLVED_VALUE = "config_resolved_value"
    const val RELAXATION_ACKNOWLEDGEMENT = "config_relaxation_acknowledgement"

    private const val VP_KEY = "check (vp_key ~ '^VP-[0-9]{2,3}$')"
    private const val OPTION_KEYS =
        "check (cardinality(option_keys) >= 1 and array_position(option_keys, null) is null)"
    private const val LEVELS = "'TENANT', 'GROUP', 'ENTITY', 'FEED', 'AP_CONNECTION', 'CLIENT_ENTITY'"
    private const val OPTION_VALUES = "check (option_values is null or jsonb_typeof(option_values) = 'object')"

    /** Every table of the current version, for a service that has none of them yet. */
    fun ddl(
        schema: String,
        appRole: String,
        platformRole: String,
        purgeRole: String,
    ): String = ddl100(schema, appRole, platformRole, purgeRole) + "\n" + upgradeTo110(schema, appRole, purgeRole) +
        "\n" + upgradeTo120(schema)

    /**
     * 1.1.0 to 1.2.0, expand-only (CS-19, CS-48): the nullable `config_resolved_value.capped_by`, a JSON array
     * of the caps a recorded value carried (`null` when nothing was capped).
     */
    fun upgradeTo120(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            alter table $s.$RESOLVED_VALUE add column capped_by jsonb
                check (capped_by is null or (jsonb_typeof(capped_by) = 'array' and jsonb_array_length(capped_by) > 0));
        """.trimIndent()
    }

    /**
     * 1.0.0 to 1.1.0, expand-only (CS-19, CS-48): adds the relaxation acknowledgement table and the nullable
     * `config_setting.relaxation_ref`; nothing existing is narrowed or dropped.
     */
    fun upgradeTo110(
        schema: String,
        appRole: String,
        purgeRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val app = SqlIdentifier.require(appRole, "app role")
        val purge = SqlIdentifier.require(purgeRole, "purge role")
        val ack = RELAXATION_ACKNOWLEDGEMENT
        return """
            alter table $s.$SETTING add column relaxation_ref uuid;
            create unique index ${SETTING}_relaxation_ref on $s.$SETTING (tenant_id, relaxation_ref)
                where relaxation_ref is not null;

            create table $s.$ack (
                seq bigint generated always as identity primary key,
                id uuid not null unique,
                relaxation_id uuid not null,
                tenant_id uuid not null,
                entity_id text,
                vp_key text not null $VP_KEY,
                scope_level text not null check (scope_level in ($LEVELS)),
                scope_id text not null check (btrim(scope_id) <> ''),
                option_keys text[] not null $OPTION_KEYS,
                option_values jsonb $OPTION_VALUES,
                role text not null check (btrim(role) <> ''),
                principal text not null check (btrim(principal) <> ''),
                effective_from date not null,
                reason text check (reason is null or btrim(reason) <> ''),
                acknowledged_at timestamptz not null,
                unique (tenant_id, relaxation_id, role),
                unique (tenant_id, relaxation_id, principal),
                check ((scope_level in ('TENANT', 'GROUP')) = (entity_id is null))
            );
            create index ${ack}_scope on $s.$ack (tenant_id, vp_key, scope_level, scope_id);
            comment on column $s.$ack.principal is 'class: personal';
            comment on column $s.$ack.reason is 'class: content';
            ${TenantRlsMigration.ddl(s, ack, policyName = "${ack}_tenant_isolation")}
            grant select, insert on $s.$ack to $app;
            grant select, delete on $s.$ack to $purge;
        """.trimIndent()
    }

    private fun ddl100(
        schema: String,
        appRole: String,
        platformRole: String,
        purgeRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val app = SqlIdentifier.require(appRole, "app role")
        val platform = SqlIdentifier.require(platformRole, "platform role")
        val purge = SqlIdentifier.require(purgeRole, "purge role")
        return """
            create table $s.$SETTING (
                seq bigint generated always as identity primary key,
                id uuid not null unique,
                tenant_id uuid not null,
                entity_id text,
                vp_key text not null $VP_KEY,
                scope_level text not null check (scope_level in ($LEVELS)),
                scope_id text not null check (btrim(scope_id) <> ''),
                option_keys text[] not null $OPTION_KEYS,
                option_values jsonb $OPTION_VALUES,
                source text not null check (source in ('OVERRIDE', 'PROFILE')),
                profile_id text,
                profile_version text,
                effective_from date not null,
                set_by text not null check (btrim(set_by) <> ''),
                reason text not null check (btrim(reason) <> ''),
                recorded_at timestamptz not null,
                check ((scope_level in ('TENANT', 'GROUP')) = (entity_id is null)),
                check ((source = 'PROFILE') = (profile_id is not null and profile_version is not null))
            );
            create index ${SETTING}_lookup on $s.$SETTING
                (tenant_id, vp_key, scope_level, scope_id, source, effective_from desc, seq desc);
            comment on column $s.$SETTING.reason is 'class: content';
            ${TenantRlsMigration.ddl(s, SETTING, policyName = "${SETTING}_tenant_isolation")}
            grant usage on schema $s to $app, $platform, $purge;
            grant select, insert on $s.$SETTING to $app;
            grant select, delete on $s.$SETTING to $purge;

            create table $s.$INSTALLATION_SETTING (
                seq bigint generated always as identity primary key,
                id uuid not null unique,
                vp_key text not null $VP_KEY,
                option_keys text[] not null $OPTION_KEYS,
                option_values jsonb $OPTION_VALUES,
                effective_from date not null,
                set_by text not null check (btrim(set_by) <> ''),
                reason text not null check (btrim(reason) <> ''),
                recorded_at timestamptz not null
            );
            create index ${INSTALLATION_SETTING}_lookup on $s.$INSTALLATION_SETTING
                (vp_key, effective_from desc, seq desc);
            comment on column $s.$INSTALLATION_SETTING.reason is 'class: content';
            grant select on $s.$INSTALLATION_SETTING to $app;
            grant select, insert on $s.$INSTALLATION_SETTING to $platform;

            create table $s.$RESOLVED_VALUE (
                id uuid primary key,
                tenant_id uuid not null,
                entity_id text,
                subject_type text not null check (subject_type ~ '^[a-z][a-z0-9_]{0,62}$'),
                subject_ref text not null check (btrim(subject_ref) <> ''),
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
                unique (tenant_id, subject_type, subject_ref, vp_key),
                check ((source = 'DEFAULT') = (source_level is null)),
                check ((source = 'DEFAULT') = (source_scope_id is null)),
                check ((source = 'DEFAULT') = (source_setting_id is null)),
                check ((source = 'PROFILE') = (profile_id is not null and profile_version is not null))
            );
            ${TenantRlsMigration.ddl(s, RESOLVED_VALUE, policyName = "${RESOLVED_VALUE}_tenant_isolation")}
            grant select, insert on $s.$RESOLVED_VALUE to $app;
            grant select, delete on $s.$RESOLVED_VALUE to $purge;
        """.trimIndent()
    }
}
