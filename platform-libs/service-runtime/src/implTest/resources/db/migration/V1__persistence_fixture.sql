-- INV-T-4224: the tables of the jOOQ convenience layer's tests. The records and POJOs are generated from this
-- migration by gst.jooq-codegen (STK-15); the integration test applies the same file to its PostgreSQL, in schema pers.
-- Flyway creates the schema `pers` and puts it on the search path (gstJooqCodegen.schema).

-- versioned
create table account (
    id uuid primary key,
    tenant_id uuid not null,
    name text not null,
    balance int not null,
    version bigint not null
);

-- versioned, named like a protected table (INV-ADR-3968 item 11)
create table audit_entry (
    id uuid primary key,
    tenant_id uuid not null,
    name text not null,
    version bigint not null
);

-- versioned, declared append-only by the test service
create table declared_log (
    id uuid primary key,
    tenant_id uuid not null,
    name text not null,
    version bigint not null
);

-- no version column: an append-only ledger table
create table ledger_entry (
    id uuid primary key,
    tenant_id uuid not null,
    amount int not null
);

-- versioned with the four audit columns
create table audited (
    id uuid primary key,
    tenant_id uuid not null,
    label text not null,
    version bigint not null,
    created_at timestamptz,
    created_by text,
    updated_at timestamptz,
    updated_by text
);

-- only some of the audit columns
create table half_audited (
    id uuid primary key,
    tenant_id uuid not null,
    version bigint not null,
    created_at timestamptz
);

-- versioned with an integer version (the Int case of the version rule)
create table counter (
    id uuid primary key,
    tenant_id uuid not null,
    name text not null,
    version integer not null
);
