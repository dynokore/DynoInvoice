package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The `create table` statements of [ConfigSchema.ddl] (split out of that object). */
internal object ConfigTablesSql {
    private const val VP_KEY = "check (vp_key ~ '^VP-[0-9]{2,3}$')"
    private const val OPTION_KEYS =
        "check (cardinality(option_keys) >= 1 and array_position(option_keys, null) is null)"
    private const val LEVELS = "'TENANT', 'GROUP', 'ENTITY', 'FEED', 'AP_CONNECTION', 'CLIENT_ENTITY'"
    private const val OPTION_VALUES = "check (option_values is null or jsonb_typeof(option_values) = 'object')"

    fun settings(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.${ConfigSchema.SETTING} (
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
            create index ${ConfigSchema.SETTING}_lookup on $s.${ConfigSchema.SETTING}
                (tenant_id, vp_key, scope_level, scope_id, source, effective_from desc, seq desc);
            create unique index ${ConfigSchema.SETTING}_relaxation_ref on $s.${ConfigSchema.SETTING} (tenant_id, relaxation_ref)
                where relaxation_ref is not null;
            comment on column $s.${ConfigSchema.SETTING}.reason is 'class: content';
            """.trimIndent()
    }

    fun installationSettings(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.${ConfigSchema.INSTALLATION_SETTING} (
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
            create index ${ConfigSchema.INSTALLATION_SETTING}_lookup on $s.${ConfigSchema.INSTALLATION_SETTING}
                (vp_key, effective_from desc, seq desc);
            comment on column $s.${ConfigSchema.INSTALLATION_SETTING}.reason is 'class: content';
            comment on table $s.${ConfigSchema.INSTALLATION_SETTING} is 'platform-scoped: no tenant data';
            """.trimIndent()
    }

    fun resolvedValues(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.${ConfigSchema.RESOLVED_VALUE} (
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
            """.trimIndent()
    }

    fun acknowledgements(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.${ConfigSchema.RELAXATION_ACKNOWLEDGEMENT} (
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
            create index ${ConfigSchema.RELAXATION_ACKNOWLEDGEMENT}_scope on $s.${ConfigSchema.RELAXATION_ACKNOWLEDGEMENT}
                (tenant_id, vp_key, scope_level, scope_id);
            comment on column $s.${ConfigSchema.RELAXATION_ACKNOWLEDGEMENT}.principal is 'class: personal';
            comment on column $s.${ConfigSchema.RELAXATION_ACKNOWLEDGEMENT}.reason is 'class: content';
            """.trimIndent()
    }
}
