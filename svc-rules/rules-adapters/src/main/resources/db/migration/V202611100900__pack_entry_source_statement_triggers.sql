-- INV-T-19509: the entry triggers of rules.pack_entry_source run once per statement over transition tables instead of
-- once per row (a 5,000-row import ran two selects on rules.pack_registry per row). The rules, messages and the error
-- code 42501 are those of the two row triggers they replace; no table, column or data changes.
drop trigger pack_entry_source_frozen on rules.pack_entry_source;
drop trigger pack_entry_source_locked on rules.pack_entry_source;
drop function rules.pack_entry_source_frozen();
drop function rules.pack_entry_source_locked();

-- The pack of a row is the one its new values name on INSERT and UPDATE and its old values name on DELETE.
-- 1. Frozen: the entries of an ARMED, SUPERSEDED or ABORTED pack version are never edited in place.
create function rules.pack_entry_source_frozen_ins() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from new_rows r join rules.pack_registry p
            on p.pack_type = r.pack_type and p.tenant_id = r.tenant_id and p.pack_version = r.pack_version
            where p.status in ('ARMED', 'SUPERSEDED', 'ABORTED')) then
        raise exception 'the entries of an armed pack version are never edited in place' using errcode = '42501';
    end if;
    return null;
end
$$;
create function rules.pack_entry_source_frozen_upd() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from new_rows r join rules.pack_registry p
            on p.pack_type = r.pack_type and p.tenant_id = r.tenant_id and p.pack_version = r.pack_version
            where p.status in ('ARMED', 'SUPERSEDED', 'ABORTED')) then
        raise exception 'the entries of an armed pack version are never edited in place' using errcode = '42501';
    end if;
    return null;
end
$$;
create function rules.pack_entry_source_frozen_del() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from old_rows r join rules.pack_registry p
            on p.pack_type = r.pack_type and p.tenant_id = r.tenant_id and p.pack_version = r.pack_version
            where p.status in ('ARMED', 'SUPERSEDED', 'ABORTED')) then
        raise exception 'the entries of an armed pack version are never edited in place' using errcode = '42501';
    end if;
    return null;
end
$$;

-- 2. Locked: the entries of a SUBMITTED or APPROVED pack are fixed; the one change left is the accepted flag of a
-- SUBMITTED pack. On UPDATE the old and new rows are paired by id; a new row with no old row is refused (fails closed).
create function rules.pack_entry_source_locked_ins() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from new_rows r join rules.pack_registry p
            on p.pack_type = r.pack_type and p.tenant_id = r.tenant_id and p.pack_version = r.pack_version
            where p.status in ('SUBMITTED', 'APPROVED')) then
        raise exception 'the entries of a submitted or approved pack are fixed' using errcode = '42501';
    end if;
    return null;
end
$$;
create function rules.pack_entry_source_locked_upd() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from new_rows n
            join rules.pack_registry p
                on p.pack_type = n.pack_type and p.tenant_id = n.tenant_id and p.pack_version = n.pack_version
            left join old_rows o on o.id = n.id
            where p.status in ('SUBMITTED', 'APPROVED')
                and not (p.status = 'SUBMITTED' and o.id is not null
                    and n.tenant_id = o.tenant_id and n.pack_type = o.pack_type
                    and n.pack_version = o.pack_version and n.entry_id = o.entry_id
                    and n.source_class = o.source_class and n.source_citation is not distinct from o.source_citation
                    and n.internal_tax_code is not distinct from o.internal_tax_code
                    and n.iras_code is not distinct from o.iras_code and n.scheme_tag is not distinct from o.scheme_tag))
        or exists (select 1 from new_rows n
            join rules.pack_registry p
                on p.pack_type = n.pack_type and p.tenant_id = n.tenant_id and p.pack_version = n.pack_version
            where p.status in ('ARMED', 'SUPERSEDED', 'ABORTED')
                and not exists (select 1 from old_rows o where o.id = n.id)) then
        raise exception 'the entries of a submitted or approved pack are fixed' using errcode = '42501';
    end if;
    return null;
end
$$;
create function rules.pack_entry_source_locked_del() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
begin
    if exists (select 1 from old_rows r join rules.pack_registry p
            on p.pack_type = r.pack_type and p.tenant_id = r.tenant_id and p.pack_version = r.pack_version
            where p.status in ('SUBMITTED', 'APPROVED')) then
        raise exception 'the entries of a submitted or approved pack are fixed' using errcode = '42501';
    end if;
    return null;
end
$$;

revoke execute on function rules.pack_entry_source_frozen_ins() from public;
revoke execute on function rules.pack_entry_source_frozen_upd() from public;
revoke execute on function rules.pack_entry_source_frozen_del() from public;
revoke execute on function rules.pack_entry_source_locked_ins() from public;
revoke execute on function rules.pack_entry_source_locked_upd() from public;
revoke execute on function rules.pack_entry_source_locked_del() from public;

-- AFTER triggers fire in name order: frozen_* before locked_*, as the row triggers did.
create trigger pack_entry_source_frozen_ins after insert on rules.pack_entry_source
    referencing new table as new_rows for each statement execute function rules.pack_entry_source_frozen_ins();
create trigger pack_entry_source_frozen_upd after update on rules.pack_entry_source
    referencing old table as old_rows new table as new_rows for each statement
    execute function rules.pack_entry_source_frozen_upd();
create trigger pack_entry_source_frozen_del after delete on rules.pack_entry_source
    referencing old table as old_rows for each statement execute function rules.pack_entry_source_frozen_del();
create trigger pack_entry_source_locked_ins after insert on rules.pack_entry_source
    referencing new table as new_rows for each statement execute function rules.pack_entry_source_locked_ins();
create trigger pack_entry_source_locked_upd after update on rules.pack_entry_source
    referencing old table as old_rows new table as new_rows for each statement
    execute function rules.pack_entry_source_locked_upd();
create trigger pack_entry_source_locked_del after delete on rules.pack_entry_source
    referencing old table as old_rows for each statement execute function rules.pack_entry_source_locked_del();
