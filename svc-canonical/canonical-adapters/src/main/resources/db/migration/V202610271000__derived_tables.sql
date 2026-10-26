SET lock_timeout = '5s';

-- V202610271000__derived_tables.sql (INV-T-5546; INV-REQ-2572; INV-NOTE-3 sections 10.2 and 10.6).
-- Lines, tax subtotals and parties of a version, derived from document_version.canonical (authoritative, never
-- rewritten) for indexed query. The columns are the scalar fields of the canonical document in platform-contracts
-- canonical/schemas/canonical-version-created-payload.schema.json at e5180d6; the contract defines no scalar field of
-- a party, so the party object is kept as jsonb. No foreign key to document_version: its key carries the partition
-- columns, which the derived key of INV-NOTE-3 section 10.6 does not. The rows of a version are written in the
-- transaction that creates it and are deleted and rewritten by the rebuild, so the runtime login deletes them.
create table canonical.document_line (
    doc_uuid uuid not null,
    version int not null check (version >= 1),
    line_number int not null check (line_number >= 1),
    tenant_id uuid not null,
    description text not null,
    quantity numeric(23, 6) not null,
    unit_price numeric(23, 6) not null,
    line_amount numeric(19, 2) not null,
    tax_code text not null,
    tax_amount numeric(19, 2) not null,
    primary key (doc_uuid, version, line_number)
);
create table canonical.document_subtotal (
    doc_uuid uuid not null,
    version int not null check (version >= 1),
    tax_code text not null,
    tenant_id uuid not null,
    taxable_amount numeric(19, 2) not null,
    tax_amount numeric(19, 2) not null,
    primary key (doc_uuid, version, tax_code)
);
create table canonical.document_party (
    doc_uuid uuid not null,
    version int not null check (version >= 1),
    role text not null check (role in ('supplier', 'buyer')),
    tenant_id uuid not null,
    party jsonb not null,
    primary key (doc_uuid, version, role)
);
comment on column canonical.document_line.description is 'class: content';
comment on column canonical.document_party.party is 'class: content';
create index document_line_tenant_tax_code on canonical.document_line (tenant_id, tax_code);

ALTER TABLE canonical.document_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_line FORCE ROW LEVEL SECURITY;
CREATE POLICY document_line_tenant_isolation ON canonical.document_line
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_subtotal ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_subtotal FORCE ROW LEVEL SECURITY;
CREATE POLICY document_subtotal_tenant_isolation ON canonical.document_subtotal
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
ALTER TABLE canonical.document_party ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_party FORCE ROW LEVEL SECURITY;
CREATE POLICY document_party_tenant_isolation ON canonical.document_party
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));

grant select, insert, delete on canonical.document_line, canonical.document_subtotal, canonical.document_party
    to canonical_runtime;
