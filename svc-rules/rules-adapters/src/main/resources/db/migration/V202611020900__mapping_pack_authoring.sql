-- V202611020900__mapping_pack_authoring.sql (INV-T-4972; INV-Q-14648 A, INV-Q-14646 A, INV-Q-14650 A).
-- Storage of a tenant mapping pack draft and its sign-off. Expand only: V1 and V3 are untouched, rules.pack_artefact is
-- not changed (it is written only at activation, by the holder-copy port).
-- Run as rules_migrate. The user IDs stored here are user IDs, never role names (INV-Q-10647).

-- 1. rules.pack_registry: the draft header. A draft has no effective date yet; the CHECK below keeps the date required
--    from the first state that evaluation can read (ARMED) on.
alter table rules.pack_registry add column label text null check (label is null or label !~ '^\s*$');
alter table rules.pack_registry add column activated_by text null check (activated_by is null or activated_by !~ '^\s*$|[[:cntrl:]]');
alter table rules.pack_registry add column activated_at timestamptz null;
-- the entity the draft belongs to: the permission of every command is checked against it (INV-Q-14652 A)
alter table rules.pack_registry add column entity_ref text null check (entity_ref is null or (entity_ref !~ '^\s*$|[[:cntrl:]]' and char_length(entity_ref) <= 64));
alter table rules.pack_registry add column content_changed_at timestamptz not null default clock_timestamp();
alter table rules.pack_registry alter column effective_from drop not null;
alter table rules.pack_registry add constraint pack_registry_effective_from_set
    check (effective_from is not null or status in ('DRAFT', 'SUBMITTED', 'APPROVED'));

-- A tenant mapping row moves only DRAFT to SUBMITTED, SUBMITTED to APPROVED, APPROVED to ARMED and, as V1 allows,
-- ARMED to SUPERSEDED or ABORTED. STAGED and ACKNOWLEDGED belong to the regulatory lifecycle and are refused here.
create function rules.pack_registry_mapping_moves() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
begin
    if new.layer = 'TENANT_MAPPING' and new.status is distinct from old.status
        and not ((old.status = 'DRAFT' and new.status = 'SUBMITTED')
            or (old.status = 'SUBMITTED' and new.status = 'APPROVED')
            or (old.status = 'APPROVED' and new.status = 'ARMED')
            or (old.status = 'ARMED' and new.status in ('SUPERSEDED', 'ABORTED'))) then
        raise exception 'a mapping pack does not move from % to %', old.status, new.status using errcode = '42501';
    end if;
    return new;
end
$$;
revoke execute on function rules.pack_registry_mapping_moves() from public;
create trigger pack_registry_mapping_moves before update on rules.pack_registry
    for each row execute function rules.pack_registry_mapping_moves();

-- 2. rules.pack_entry_source: the entry columns of the mapping contents schema.
alter table rules.pack_entry_source add column internal_tax_code text null;
alter table rules.pack_entry_source add column iras_code text null;
alter table rules.pack_entry_source add column scheme_tag text null;
alter table rules.pack_entry_source add constraint pack_entry_source_mapping_codes
    check (pack_type <> 'MAPPING'
        or (internal_tax_code is not null and internal_tax_code !~ '^\s*$'
            and iras_code is not null and iras_code !~ '^\s*$'));
alter table rules.pack_entry_source add constraint pack_entry_source_internal_code_key
    unique (pack_type, tenant_id, pack_version, internal_tax_code);

-- The entries of a SUBMITTED or APPROVED pack are fixed: the one change left is the accepted flag of a SUBMITTED pack.
create function rules.pack_entry_source_locked() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
declare
    v_status text;
begin
    select p.status into v_status from rules.pack_registry p
        where p.pack_type = coalesce(new.pack_type, old.pack_type) and p.tenant_id = coalesce(new.tenant_id, old.tenant_id)
            and p.pack_version = coalesce(new.pack_version, old.pack_version);
    if v_status = 'SUBMITTED' and tg_op = 'UPDATE'
        and new.id = old.id and new.tenant_id = old.tenant_id and new.pack_type = old.pack_type
        and new.pack_version = old.pack_version and new.entry_id = old.entry_id
        and new.source_class = old.source_class and new.source_citation is not distinct from old.source_citation
        and new.internal_tax_code is not distinct from old.internal_tax_code
        and new.iras_code is not distinct from old.iras_code and new.scheme_tag is not distinct from old.scheme_tag then
        return new;
    end if;
    if v_status in ('SUBMITTED', 'APPROVED') then
        raise exception 'the entries of a submitted or approved pack are fixed' using errcode = '42501';
    end if;
    return coalesce(new, old);
end
$$;
revoke execute on function rules.pack_entry_source_locked() from public;
create trigger pack_entry_source_locked before insert or update or delete on rules.pack_entry_source
    for each row execute function rules.pack_entry_source_locked();

-- 3. rules.dry_run_delta (INV-Q-14646 A): the stored result of a dry run; INV-T-4974 fills it. Relational only.
create table rules.dry_run_delta (
    id             uuid        primary key,
    tenant_id      uuid        not null,
    pack_type      text        not null check (pack_type !~ '^\s*$'),
    pack_version   text        not null check (pack_version !~ '^\s*$'),
    state          text        not null check (state in ('RUNNING', 'COMPLETE', 'SAMPLE_UNAVAILABLE')),
    started_by     text        not null check (started_by !~ '^\s*$|[[:cntrl:]]'),
    started_at     timestamptz not null default clock_timestamp(),
    completed_at   timestamptz null,
    changed_count  integer     null check (changed_count is null or changed_count >= 0),
    constraint dry_run_delta_complete check ((state = 'COMPLETE') = (completed_at is not null and changed_count is not null))
);
create index dry_run_delta_pack_idx on rules.dry_run_delta (tenant_id, pack_type, pack_version, completed_at);

create function rules.dry_run_delta_complete_fixed() returns trigger
language plpgsql set search_path = pg_catalog, rules, pg_temp as $$
begin
    if old.state = 'COMPLETE' then
        raise exception 'a complete dry-run delta is never updated or deleted' using errcode = '42501';
    end if;
    return coalesce(new, old);
end
$$;
revoke execute on function rules.dry_run_delta_complete_fixed() from public;
create trigger dry_run_delta_complete_fixed before update or delete on rules.dry_run_delta
    for each row execute function rules.dry_run_delta_complete_fixed();

-- 4. rules.mapping_entry_acceptance (INV-REQ-729): one row per entry, scoped to the entry, with rationale and expiry.
create table rules.mapping_entry_acceptance (
    tenant_id             uuid        not null,
    pack_type             text        not null,
    pack_version          text        not null,
    entry_id              text        not null check (entry_id !~ '^\s*$'),
    rationale             text        not null check (rationale !~ '^\s*$' and char_length(rationale) <= 500),
    expires_on            date        not null,
    accepted_by           text        not null check (accepted_by !~ '^\s*$|[[:cntrl:]]'),
    accepted_at           timestamptz not null default clock_timestamp(),
    accepted_iras_code    text        not null check (accepted_iras_code !~ '^\s*$'),
    accepted_source_class text        not null check (accepted_source_class in ('PRIMARY', 'SECONDARY', 'INFERRED')),
    primary key (pack_type, tenant_id, pack_version, entry_id),
    foreign key (pack_type, tenant_id, pack_version, entry_id)
        references rules.pack_entry_source (pack_type, tenant_id, pack_version, entry_id)
);

-- 5. rules.mapping_pack_approval (INV-REQ-380 b2): the approval attaches to the dry-run delta it signs. Insert and select only.
create table rules.mapping_pack_approval (
    tenant_id                         uuid        not null,
    pack_type                         text        not null,
    pack_version                      text        not null,
    approved_by                       text        not null check (approved_by !~ '^\s*$|[[:cntrl:]]'),
    approved_at                       timestamptz not null default clock_timestamp(),
    dry_run_delta_id                  uuid        not null references rules.dry_run_delta (id),
    sole_operator                     boolean     not null,
    sole_operator_ack_ref             text        null check (sole_operator_ack_ref is null or sole_operator_ack_ref !~ '^\s*$'),
    option_key                        text        not null check (option_key !~ '^\s*$'),
    outstanding_client_acknowledgement boolean    not null,
    outstanding_client_approval       boolean     not null,
    primary key (pack_type, tenant_id, pack_version, approved_by),
    foreign key (pack_type, tenant_id, pack_version) references rules.pack_registry (pack_type, tenant_id, pack_version)
);

-- Row-level security: a bound tenant reads and writes its own rows only (the V1 tenant policies).
alter table rules.dry_run_delta enable row level security;
alter table rules.dry_run_delta force row level security;
alter table rules.mapping_entry_acceptance enable row level security;
alter table rules.mapping_entry_acceptance force row level security;
alter table rules.mapping_pack_approval enable row level security;
alter table rules.mapping_pack_approval force row level security;

create policy dry_run_delta_tenant_read on rules.dry_run_delta for select
    using (tenant_id = (select rules.bound_tenant()));
create policy dry_run_delta_tenant_insert on rules.dry_run_delta for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy dry_run_delta_tenant_update on rules.dry_run_delta for update
    using (tenant_id = (select rules.bound_tenant())) with check (tenant_id = (select rules.bound_tenant()));
create policy mapping_entry_acceptance_tenant_read on rules.mapping_entry_acceptance for select
    using (tenant_id = (select rules.bound_tenant()));
create policy mapping_entry_acceptance_tenant_insert on rules.mapping_entry_acceptance for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy mapping_entry_acceptance_tenant_update on rules.mapping_entry_acceptance for update
    using (tenant_id = (select rules.bound_tenant())) with check (tenant_id = (select rules.bound_tenant()));
create policy mapping_pack_approval_tenant_read on rules.mapping_pack_approval for select
    using (tenant_id = (select rules.bound_tenant()));
create policy mapping_pack_approval_tenant_insert on rules.mapping_pack_approval for insert
    with check (tenant_id = (select rules.bound_tenant()));

-- Least privilege: nothing for PUBLIC, no DELETE; the approval is immutable evidence (NIST SP 800-53 AU-10).
revoke all on rules.dry_run_delta, rules.mapping_entry_acceptance, rules.mapping_pack_approval from public;
grant select, insert, update on rules.dry_run_delta to rules_runtime;
grant select, insert, update on rules.mapping_entry_acceptance to rules_runtime;
grant select, insert on rules.mapping_pack_approval to rules_runtime;
