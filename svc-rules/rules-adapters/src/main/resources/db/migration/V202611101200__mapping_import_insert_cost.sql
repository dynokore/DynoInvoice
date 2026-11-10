-- V202611101200__mapping_import_insert_cost.sql (INV-T-4973; INV-AC-3484). Expand only, no data change.
-- A 5,000-row import ran the two internal foreign-key constraint triggers of rules.pack_entry_source once per row
-- (55.5 of 105 ms per 500-row insert). Both foreign keys are replaced by statement-level triggers over transition
-- tables with the same refusals: SQLSTATE 23503, a message that names the replaced constraint, a FOR KEY SHARE lock on
-- the parent rows found, and MATCH SIMPLE for a null import_ref. The replaced constraints are
-- pack_entry_source_import_ref (tenant_id, import_ref) -> rules.mapping_import_record (tenant_id, import_id), which the
-- previous migration does not create, and pack_entry_source_pack_type_tenant_id_pack_version_fkey
-- (pack_type, tenant_id, pack_version) -> rules.pack_registry, created by V1.
--
-- Every function is SECURITY DEFINER and owned by rules_sys, a role that holds only what the bodies need, never by the
-- schema owner rules_migrate (INV-ADR-3968 item 6a; PostgreSQL "Writing SECURITY DEFINER Functions Safely"; least
-- privilege). A definer function takes the tenant from the transition rows and compares tenant_id by equality in every
-- join, never from the session. The grants and policies it needs are named below, each with its reason.
-- The lock is taken first and the check follows in a later statement, so a parent row deleted by a concurrent
-- transaction that the lock waited for is not found by the check (READ COMMITTED takes a new snapshot per statement).

-- Child side: an entry names an existing import record (when import_ref is set) and an existing pack.
create function rules.pack_entry_source_ref_ins() returns trigger
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
begin
    perform 1 from rules.mapping_import_record m
        where (m.tenant_id, m.import_id) in (select r.tenant_id, r.import_ref from new_rows r where r.import_ref is not null)
        for key share of m;
    if exists (select 1 from new_rows r where r.import_ref is not null and not exists (
            select 1 from rules.mapping_import_record m where m.tenant_id = r.tenant_id and m.import_id = r.import_ref)) then
        raise exception 'insert or update on table "pack_entry_source" violates foreign key constraint "pack_entry_source_import_ref"'
            using errcode = '23503';
    end if;
    perform 1 from rules.pack_registry p
        where (p.pack_type, p.tenant_id, p.pack_version) in (select r.pack_type, r.tenant_id, r.pack_version from new_rows r)
        for key share of p;
    if exists (select 1 from new_rows r where not exists (
            select 1 from rules.pack_registry p
                where p.pack_type = r.pack_type and p.tenant_id = r.tenant_id and p.pack_version = r.pack_version)) then
        raise exception 'insert or update on table "pack_entry_source" violates foreign key constraint "pack_entry_source_pack_type_tenant_id_pack_version_fkey"'
            using errcode = '23503';
    end if;
    return null;
end
$$;

-- On UPDATE only the rows whose reference columns changed are checked, as the foreign keys did (the old row is found by id).
create function rules.pack_entry_source_ref_upd() returns trigger
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
begin
    perform 1 from rules.mapping_import_record m
        where (m.tenant_id, m.import_id) in (
            select n.tenant_id, n.import_ref from new_rows n left join old_rows o on o.id = n.id
                where n.import_ref is not null
                    and (o.id is null or (o.tenant_id, o.import_ref) is distinct from (n.tenant_id, n.import_ref)))
        for key share of m;
    if exists (select 1 from new_rows n left join old_rows o on o.id = n.id
            where n.import_ref is not null
                and (o.id is null or (o.tenant_id, o.import_ref) is distinct from (n.tenant_id, n.import_ref))
                and not exists (select 1 from rules.mapping_import_record m
                    where m.tenant_id = n.tenant_id and m.import_id = n.import_ref)) then
        raise exception 'insert or update on table "pack_entry_source" violates foreign key constraint "pack_entry_source_import_ref"'
            using errcode = '23503';
    end if;
    perform 1 from rules.pack_registry p
        where (p.pack_type, p.tenant_id, p.pack_version) in (
            select n.pack_type, n.tenant_id, n.pack_version from new_rows n left join old_rows o on o.id = n.id
                where o.id is null
                    or (o.pack_type, o.tenant_id, o.pack_version) is distinct from (n.pack_type, n.tenant_id, n.pack_version))
        for key share of p;
    if exists (select 1 from new_rows n left join old_rows o on o.id = n.id
            where (o.id is null
                    or (o.pack_type, o.tenant_id, o.pack_version) is distinct from (n.pack_type, n.tenant_id, n.pack_version))
                and not exists (select 1 from rules.pack_registry p
                    where p.pack_type = n.pack_type and p.tenant_id = n.tenant_id and p.pack_version = n.pack_version)) then
        raise exception 'insert or update on table "pack_entry_source" violates foreign key constraint "pack_entry_source_pack_type_tenant_id_pack_version_fkey"'
            using errcode = '23503';
    end if;
    return null;
end
$$;

-- Parent side (NO ACTION): a delete, or an update of the referenced key columns, of a row that an entry still references.
-- A key that stays in the table after the statement (updated to itself, or swapped with another row) is allowed.
create function rules.pack_registry_entry_ref_upd() returns trigger
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from old_rows o
            where not exists (select 1 from new_rows n
                where n.pack_type = o.pack_type and n.tenant_id = o.tenant_id and n.pack_version = o.pack_version)
                and exists (select 1 from rules.pack_entry_source e
                    where e.pack_type = o.pack_type and e.tenant_id = o.tenant_id and e.pack_version = o.pack_version)) then
        raise exception 'update or delete on table "pack_registry" violates foreign key constraint "pack_entry_source_pack_type_tenant_id_pack_version_fkey" on table "pack_entry_source"'
            using errcode = '23503';
    end if;
    return null;
end
$$;
create function rules.pack_registry_entry_ref_del() returns trigger
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from old_rows o where exists (select 1 from rules.pack_entry_source e
            where e.pack_type = o.pack_type and e.tenant_id = o.tenant_id and e.pack_version = o.pack_version)) then
        raise exception 'update or delete on table "pack_registry" violates foreign key constraint "pack_entry_source_pack_type_tenant_id_pack_version_fkey" on table "pack_entry_source"'
            using errcode = '23503';
    end if;
    return null;
end
$$;
create function rules.mapping_import_record_entry_ref_upd() returns trigger
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from old_rows o
            where not exists (select 1 from new_rows n where n.tenant_id = o.tenant_id and n.import_id = o.import_id)
                and exists (select 1 from rules.pack_entry_source e
                    where e.tenant_id = o.tenant_id and e.import_ref = o.import_id)) then
        raise exception 'update or delete on table "mapping_import_record" violates foreign key constraint "pack_entry_source_import_ref" on table "pack_entry_source"'
            using errcode = '23503';
    end if;
    return null;
end
$$;
create function rules.mapping_import_record_entry_ref_del() returns trigger
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from old_rows o where exists (select 1 from rules.pack_entry_source e
            where e.tenant_id = o.tenant_id and e.import_ref = o.import_id)) then
        raise exception 'update or delete on table "mapping_import_record" violates foreign key constraint "pack_entry_source_import_ref" on table "pack_entry_source"'
            using errcode = '23503';
    end if;
    return null;
end
$$;

revoke execute on function rules.pack_entry_source_ref_ins() from public;
revoke execute on function rules.pack_entry_source_ref_upd() from public;
revoke execute on function rules.pack_registry_entry_ref_upd() from public;
revoke execute on function rules.pack_registry_entry_ref_del() from public;
revoke execute on function rules.mapping_import_record_entry_ref_upd() from public;
revoke execute on function rules.mapping_import_record_entry_ref_del() from public;

-- rules_sys privileges for the six bodies (INV-ADR-3968 item 6a, OWASP ASVS V4.1 least privilege). No policy is added:
-- the existing tenant policies have no TO clause, so they bind rules_sys to the signed tenant like every other role.
grant select (tenant_id, import_id) on rules.mapping_import_record to rules_sys;       -- key columns read by the checks
grant update (import_id) on rules.mapping_import_record to rules_sys;                  -- needed only for FOR KEY SHARE
grant select (tenant_id, pack_type, pack_version, import_ref) on rules.pack_entry_source to rules_sys;  -- parent-side key read

grant create on schema rules to rules_sys;
alter function rules.pack_entry_source_ref_ins() owner to rules_sys;
alter function rules.pack_entry_source_ref_upd() owner to rules_sys;
alter function rules.pack_registry_entry_ref_upd() owner to rules_sys;
alter function rules.pack_registry_entry_ref_del() owner to rules_sys;
alter function rules.mapping_import_record_entry_ref_upd() owner to rules_sys;
alter function rules.mapping_import_record_entry_ref_del() owner to rules_sys;
revoke create on schema rules from rules_sys;

-- AFTER triggers fire in name order: frozen_*, locked_*, then ref_*, as the constraint triggers fired after them.
create trigger pack_entry_source_ref_ins after insert on rules.pack_entry_source
    referencing new table as new_rows for each statement execute function rules.pack_entry_source_ref_ins();
create trigger pack_entry_source_ref_upd after update on rules.pack_entry_source
    referencing old table as old_rows new table as new_rows for each statement
    execute function rules.pack_entry_source_ref_upd();
create trigger pack_registry_entry_ref_upd after update on rules.pack_registry
    referencing old table as old_rows new table as new_rows for each statement
    execute function rules.pack_registry_entry_ref_upd();
create trigger pack_registry_entry_ref_del after delete on rules.pack_registry
    referencing old table as old_rows for each statement execute function rules.pack_registry_entry_ref_del();
create trigger mapping_import_record_entry_ref_upd after update on rules.mapping_import_record
    referencing old table as old_rows new table as new_rows for each statement
    execute function rules.mapping_import_record_entry_ref_upd();
create trigger mapping_import_record_entry_ref_del after delete on rules.mapping_import_record
    referencing old table as old_rows for each statement execute function rules.mapping_import_record_entry_ref_del();

alter table rules.pack_entry_source drop constraint pack_entry_source_pack_type_tenant_id_pack_version_fkey;
