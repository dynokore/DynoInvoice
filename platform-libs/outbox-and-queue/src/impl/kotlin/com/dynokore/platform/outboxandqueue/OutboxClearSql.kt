package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The read-model clear functions of [OutboxSchema.readModelClearFunction] (split out of that object). */
internal object OutboxClearSql {
    fun clearAll(
        schema: String,
        table: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val name = SqlIdentifier.require("${t}_full_sync_clear", "clear function name")
        val selectPolicy = SqlIdentifier.require("${t}_sys_full_sync_select", "policy name")
        val deletePolicy = SqlIdentifier.require("${t}_sys_full_sync", "policy name")
        return """
            grant usage on schema $s to $sys;
            drop policy if exists $selectPolicy on $s.$t;
            drop policy if exists $deletePolicy on $s.$t;
            create policy $deletePolicy on $s.$t for delete to $sys using (true);
            grant delete on $s.$t to $sys;
            create or replace function $s.$name() returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
            begin
                delete from $s.$t;
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$name() owner to $sys;
            revoke all on function $s.$name() from public;
            grant execute on function $s.$name() to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    fun clearByColumn(
        schema: String,
        table: String,
        filterColumn: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val column = SqlIdentifier.require(filterColumn, "filter column")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val name = SqlIdentifier.require("${t}_full_sync_clear", "clear function name")
        val selectPolicy = SqlIdentifier.require("${t}_sys_full_sync_select", "policy name")
        val deletePolicy = SqlIdentifier.require("${t}_sys_full_sync", "policy name")
        return """
            grant usage on schema $s to $sys;
            drop policy if exists $selectPolicy on $s.$t;
            drop policy if exists $deletePolicy on $s.$t;
            create policy $deletePolicy on $s.$t for delete to $sys using (true);
            grant delete on $s.$t to $sys;
            create policy $selectPolicy on $s.$t for select to $sys using (true);
            grant select ($column) on $s.$t to $sys;
            create or replace function $s.$name(p_value text) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                deleted bigint;
                v $s.$t.$column%type;
            begin
                v := p_value;
                if p_value is null or p_value::text ~ '${BlankText.SQL_BLANK}' then
                    raise exception 'the filter value of $s.$t must not be blank' using errcode = '22023';
                end if;
                delete from $s.$t where $column = v;
                get diagnostics deleted = row_count;
                return deleted;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$name(text) owner to $sys;
            revoke all on function $s.$name(text) from public;
            grant execute on function $s.$name(text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }
}
