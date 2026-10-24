-- V3__document.sql (INV-T-5545; INV-REQ-637, INV-REQ-640, INV-REQ-641, INV-REQ-2574; INV-NOTE-3 sections 10.2 to 10.5).
-- The logical document and its immutable versions. Both tables are range-partitioned on partition_date (the
-- transaction date of version 1, never changed) and sub-partitioned by hash of entity_id; a default partition takes any
-- date outside the created ranges. Creating future ranges and detaching partitions at retention end are not in this file.
-- A PostgreSQL primary key or unique index of a partitioned table must contain every partition key column, so the
-- primary key of document is (doc_uuid, partition_date, entity_id) and the INV-NOTE-3 section 10.3 key carries
-- partition_date as well; the trigger document_identity_check enforces both keys as written, across partitions.
create table canonical.document (
    doc_uuid uuid not null,
    tenant_id uuid not null,
    entity_id uuid not null,
    business_unit_id uuid,
    direction text not null check (direction in ('SALE', 'PURCHASE')),
    document_type_code smallint not null,
    document_number varchar(100) not null,
    issuer_party_key varchar(64) not null,
    fiscal_year smallint not null,
    internal_reference varchar(64),
    partition_date date not null,
    transaction_date date not null,
    prescribed_period_id uuid,
    current_version int not null check (current_version >= 1),
    state text not null check (state in ('INGESTED', 'HELD', 'BLOCKED', 'NOT_SUBMITTABLE', 'READY')),
    primary key (doc_uuid, partition_date, entity_id),
    constraint document_identity_key unique (tenant_id, entity_id, direction, document_type_code, issuer_party_key,
        document_number, fiscal_year, partition_date)
) partition by range (partition_date);
create table canonical.document_version (
    doc_uuid uuid not null,
    version int not null check (version >= 1),
    tenant_id uuid not null,
    entity_id uuid not null,
    partition_date date not null,
    canonical jsonb not null,
    scope_verdict text not null check (scope_verdict in ('IN_SCOPE', 'EXCLUDED_TXN', 'OUT_OF_SCOPE')),
    scope_pack_version varchar(32) not null,
    mapping_pack_version varchar(32) not null,
    mde_pack_version varchar(32),
    specification_version text not null check (specification_version in ('PINT_SG', 'SG_PEPPOL_BIS')),
    created_at timestamptz not null default now(),
    created_by text not null,
    primary key (doc_uuid, version, partition_date, entity_id),
    foreign key (doc_uuid, partition_date, entity_id) references canonical.document (doc_uuid, partition_date, entity_id)
) partition by range (partition_date);
comment on column canonical.document_version.canonical is 'class: content';

create table canonical.document_y2025 partition of canonical.document for values from ('2025-01-01') to ('2026-01-01') partition by hash (entity_id);
create table canonical.document_y2025_h0 partition of canonical.document_y2025 for values with (modulus 4, remainder 0);
create table canonical.document_y2025_h1 partition of canonical.document_y2025 for values with (modulus 4, remainder 1);
create table canonical.document_y2025_h2 partition of canonical.document_y2025 for values with (modulus 4, remainder 2);
create table canonical.document_y2025_h3 partition of canonical.document_y2025 for values with (modulus 4, remainder 3);
create table canonical.document_y2026 partition of canonical.document for values from ('2026-01-01') to ('2027-01-01') partition by hash (entity_id);
create table canonical.document_y2026_h0 partition of canonical.document_y2026 for values with (modulus 4, remainder 0);
create table canonical.document_y2026_h1 partition of canonical.document_y2026 for values with (modulus 4, remainder 1);
create table canonical.document_y2026_h2 partition of canonical.document_y2026 for values with (modulus 4, remainder 2);
create table canonical.document_y2026_h3 partition of canonical.document_y2026 for values with (modulus 4, remainder 3);
create table canonical.document_y2027 partition of canonical.document for values from ('2027-01-01') to ('2028-01-01') partition by hash (entity_id);
create table canonical.document_y2027_h0 partition of canonical.document_y2027 for values with (modulus 4, remainder 0);
create table canonical.document_y2027_h1 partition of canonical.document_y2027 for values with (modulus 4, remainder 1);
create table canonical.document_y2027_h2 partition of canonical.document_y2027 for values with (modulus 4, remainder 2);
create table canonical.document_y2027_h3 partition of canonical.document_y2027 for values with (modulus 4, remainder 3);
create table canonical.document_default partition of canonical.document default;
create table canonical.document_version_y2025 partition of canonical.document_version for values from ('2025-01-01') to ('2026-01-01') partition by hash (entity_id);
create table canonical.document_version_y2025_h0 partition of canonical.document_version_y2025 for values with (modulus 4, remainder 0);
create table canonical.document_version_y2025_h1 partition of canonical.document_version_y2025 for values with (modulus 4, remainder 1);
create table canonical.document_version_y2025_h2 partition of canonical.document_version_y2025 for values with (modulus 4, remainder 2);
create table canonical.document_version_y2025_h3 partition of canonical.document_version_y2025 for values with (modulus 4, remainder 3);
create table canonical.document_version_y2026 partition of canonical.document_version for values from ('2026-01-01') to ('2027-01-01') partition by hash (entity_id);
create table canonical.document_version_y2026_h0 partition of canonical.document_version_y2026 for values with (modulus 4, remainder 0);
create table canonical.document_version_y2026_h1 partition of canonical.document_version_y2026 for values with (modulus 4, remainder 1);
create table canonical.document_version_y2026_h2 partition of canonical.document_version_y2026 for values with (modulus 4, remainder 2);
create table canonical.document_version_y2026_h3 partition of canonical.document_version_y2026 for values with (modulus 4, remainder 3);
create table canonical.document_version_y2027 partition of canonical.document_version for values from ('2027-01-01') to ('2028-01-01') partition by hash (entity_id);
create table canonical.document_version_y2027_h0 partition of canonical.document_version_y2027 for values with (modulus 4, remainder 0);
create table canonical.document_version_y2027_h1 partition of canonical.document_version_y2027 for values with (modulus 4, remainder 1);
create table canonical.document_version_y2027_h2 partition of canonical.document_version_y2027 for values with (modulus 4, remainder 2);
create table canonical.document_version_y2027_h3 partition of canonical.document_version_y2027 for values with (modulus 4, remainder 3);
create table canonical.document_version_default partition of canonical.document_version default;

create index document_entity_document_number on canonical.document (entity_id, document_number);
create index document_entity_transaction_date on canonical.document (entity_id, transaction_date);
create function canonical.document_identity_check() returns trigger
language plpgsql security definer set search_path = pg_catalog, canonical, pg_temp as $$
begin
    perform pg_advisory_xact_lock(hashtextextended('document_identity' || chr(31) || concat_ws(chr(31), new.tenant_id,
        new.entity_id, new.direction, new.document_type_code, new.issuer_party_key, new.document_number,
        new.fiscal_year), 0));
    if exists (select 1 from canonical.document d
            where d.tenant_id = new.tenant_id and d.entity_id = new.entity_id and d.direction = new.direction
              and d.document_type_code = new.document_type_code and d.issuer_party_key = new.issuer_party_key
              and d.document_number = new.document_number and d.fiscal_year = new.fiscal_year) then
        raise exception 'document identity already exists' using errcode = '23505', constraint = 'document_identity_key';
    end if;
    perform pg_advisory_xact_lock(hashtextextended('document_doc_uuid' || chr(31) || new.doc_uuid::text, 0));
    if exists (select 1 from canonical.document d where d.doc_uuid = new.doc_uuid) then
        raise exception 'doc_uuid already exists' using errcode = '23505', constraint = 'document_pkey';
    end if;
    return new;
end
$$;
-- The function runs as its owner canonical_sys so that the doc_uuid check sees the rows of every tenant although
-- document forces row security: the select policy and column grant below give that one NOLOGIN role the columns of the
-- two checks. The runtime login cannot assume the role, and gets no BYPASSRLS and no privilege on another table.
create policy document_sys_identity_select on canonical.document for select to canonical_sys using (true);
grant select (doc_uuid, tenant_id, entity_id, direction, document_type_code, issuer_party_key, document_number,
    fiscal_year) on canonical.document to canonical_sys;
-- ALTER ... OWNER TO needs CREATE on the schema for the new owner; it is granted for that statement only.
grant create on schema canonical to canonical_sys;
alter function canonical.document_identity_check() owner to canonical_sys;
revoke create on schema canonical from canonical_sys;
revoke all on function canonical.document_identity_check() from public;
create trigger document_identity_check before insert on canonical.document
    for each row execute function canonical.document_identity_check();

ALTER TABLE canonical.document ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document FORCE ROW LEVEL SECURITY;
CREATE POLICY document_tenant_isolation ON canonical.document
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_tenant_isolation ON canonical.document_version
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2025 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2025 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2025_tenant_isolation ON canonical.document_y2025
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2025_h0 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2025_h0 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2025_h0_tenant_isolation ON canonical.document_y2025_h0
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2025_h1 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2025_h1 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2025_h1_tenant_isolation ON canonical.document_y2025_h1
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2025_h2 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2025_h2 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2025_h2_tenant_isolation ON canonical.document_y2025_h2
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2025_h3 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2025_h3 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2025_h3_tenant_isolation ON canonical.document_y2025_h3
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2026 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2026 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2026_tenant_isolation ON canonical.document_y2026
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2026_h0 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2026_h0 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2026_h0_tenant_isolation ON canonical.document_y2026_h0
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2026_h1 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2026_h1 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2026_h1_tenant_isolation ON canonical.document_y2026_h1
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2026_h2 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2026_h2 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2026_h2_tenant_isolation ON canonical.document_y2026_h2
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2026_h3 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2026_h3 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2026_h3_tenant_isolation ON canonical.document_y2026_h3
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2027 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2027 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2027_tenant_isolation ON canonical.document_y2027
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2027_h0 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2027_h0 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2027_h0_tenant_isolation ON canonical.document_y2027_h0
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2027_h1 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2027_h1 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2027_h1_tenant_isolation ON canonical.document_y2027_h1
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2027_h2 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2027_h2 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2027_h2_tenant_isolation ON canonical.document_y2027_h2
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_y2027_h3 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_y2027_h3 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_y2027_h3_tenant_isolation ON canonical.document_y2027_h3
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_default ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_default FORCE ROW LEVEL SECURITY;
CREATE POLICY document_default_tenant_isolation ON canonical.document_default
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2025 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2025 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2025_tenant_isolation ON canonical.document_version_y2025
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2025_h0 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2025_h0 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2025_h0_tenant_isolation ON canonical.document_version_y2025_h0
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2025_h1 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2025_h1 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2025_h1_tenant_isolation ON canonical.document_version_y2025_h1
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2025_h2 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2025_h2 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2025_h2_tenant_isolation ON canonical.document_version_y2025_h2
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2025_h3 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2025_h3 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2025_h3_tenant_isolation ON canonical.document_version_y2025_h3
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2026 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2026 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2026_tenant_isolation ON canonical.document_version_y2026
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2026_h0 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2026_h0 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2026_h0_tenant_isolation ON canonical.document_version_y2026_h0
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2026_h1 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2026_h1 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2026_h1_tenant_isolation ON canonical.document_version_y2026_h1
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2026_h2 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2026_h2 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2026_h2_tenant_isolation ON canonical.document_version_y2026_h2
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2026_h3 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2026_h3 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2026_h3_tenant_isolation ON canonical.document_version_y2026_h3
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2027 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2027 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2027_tenant_isolation ON canonical.document_version_y2027
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2027_h0 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2027_h0 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2027_h0_tenant_isolation ON canonical.document_version_y2027_h0
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2027_h1 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2027_h1 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2027_h1_tenant_isolation ON canonical.document_version_y2027_h1
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2027_h2 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2027_h2 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2027_h2_tenant_isolation ON canonical.document_version_y2027_h2
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_y2027_h3 ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_y2027_h3 FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_y2027_h3_tenant_isolation ON canonical.document_version_y2027_h3
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_version_default ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_version_default FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_default_tenant_isolation ON canonical.document_version_default
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
-- A correction is a new version (INV-REQ-637): the runtime login inserts versions and never updates them, and updates
-- four columns of document only. partition_date is not among them.
grant select, insert on canonical.document_version to canonical_runtime;
grant select, insert, update (current_version, state, transaction_date, prescribed_period_id) on canonical.document
    to canonical_runtime;
