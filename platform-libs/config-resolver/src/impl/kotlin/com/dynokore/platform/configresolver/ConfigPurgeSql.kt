package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The purge functions of [ConfigSchema.ddl] (split out of that object). */
internal object ConfigPurgeSql {
    fun subject(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${ConfigSchema.PURGE_SUBJECT}(p_subject_type text, p_subject_ref text) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                v_actor text := (select $s.bound_actor());
                v_tenant uuid := (select $s.bound_tenant());
                v_deleted bigint;
            begin
                if v_actor is null or v_actor ~ '${BlankText.SQL_NOT_ACTOR}' then
                    raise exception 'the bound actor is not set: a purge records its actor' using errcode = '42501';
                end if;
                if v_tenant is null or v_tenant = '${ConfigSchema.PLATFORM}'::uuid then
                    raise exception 'a purge runs in a transaction of the tenant concerned' using errcode = '42501';
                end if;
                if p_subject_type is null or p_subject_ref is null then
                    raise exception 'the subject is required' using errcode = '22023';
                end if;
                delete from $s.${ConfigSchema.RESOLVED_VALUE}
                where tenant_id = v_tenant and subject_type = p_subject_type and subject_ref = p_subject_ref;
                get diagnostics v_deleted = row_count;
                return v_deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${ConfigSchema.PURGE_SUBJECT}(text, text) owner to $sys;
            revoke all on function $s.${ConfigSchema.PURGE_SUBJECT}(text, text) from public;
            grant execute on function $s.${ConfigSchema.PURGE_SUBJECT}(text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    fun tenant(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${ConfigSchema.PURGE_TENANT}(p_tenant uuid)
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
                if v_tenant is null or v_tenant = '${ConfigSchema.PLATFORM}'::uuid or p_tenant is distinct from v_tenant then
                    raise exception 'a tenant is purged only in its own transaction' using errcode = '42501';
                end if;
                delete from $s.${ConfigSchema.RESOLVED_VALUE} where tenant_id = v_tenant;
                get diagnostics v_resolved = row_count;
                delete from $s.${ConfigSchema.SETTING} where tenant_id = v_tenant;
                get diagnostics v_settings = row_count;
                delete from $s.${ConfigSchema.RELAXATION_ACKNOWLEDGEMENT} where tenant_id = v_tenant;
                get diagnostics v_acknowledgements = row_count;
                return query select v_resolved, v_settings, v_acknowledgements;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${ConfigSchema.PURGE_TENANT}(uuid) owner to $sys;
            revoke all on function $s.${ConfigSchema.PURGE_TENANT}(uuid) from public;
            grant execute on function $s.${ConfigSchema.PURGE_TENANT}(uuid) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }
}
