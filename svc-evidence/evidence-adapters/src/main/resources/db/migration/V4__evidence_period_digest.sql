-- V4__evidence_period_digest.sql (INV-T-7260; INV-Q-7251 A (2)).
--
-- The period digest of a filed return and its three chain rows. Both stores are append-only and tenant-isolated the
-- way the other evidence tables are (the trailer of EvidenceLedgerSchema.trailerDdl: row-level security, REVOKE
-- UPDATE/DELETE/TRUNCATE, the append-only trigger, then the grant). A chain row names its digest by the same four
-- key columns. There is no foreign key from period_digest_chain to period_digest (INV-Q-12053): both tables belong to
-- the separable evidence store, the schema as built relates no tables by a foreign key, the generic separable-store
-- test inserts rows into each table independently, and the referential rule is tested in the consumer
-- (PeriodDigestConsumerTest).

create table evidence.period_digest (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    digest_seq integer not null check (digest_seq >= 1),
    return_version integer not null check (return_version >= 1),
    filed_at timestamptz not null,
    recorded_at timestamptz not null,
    source_event_id text not null,
    digest text not null check (digest ~ '^sha256:[0-9a-f]{64}$'),
    primary key (tenant_id, entity_ref, period_ref, digest_seq),
    unique (tenant_id, source_event_id)
);

ALTER TABLE evidence.period_digest ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.period_digest FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.period_digest
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.period_digest FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.period_digest_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER period_digest_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.period_digest
    FOR EACH ROW EXECUTE FUNCTION evidence.period_digest_immutable();
grant select, insert on evidence.period_digest to evidence_runtime;

create table evidence.period_digest_chain (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    digest_seq integer not null,
    evidence_type text not null check (evidence_type in ('PAYLOAD', 'ACKNOWLEDGEMENT', 'DECISION_TRACE')),
    chain_length bigint not null check (chain_length >= 0),
    tail_hash text not null check ((chain_length = 0 and tail_hash = '-') or (chain_length > 0 and tail_hash ~ '^[0-9a-f]{64}$')),
    primary key (tenant_id, entity_ref, period_ref, digest_seq, evidence_type)
);

ALTER TABLE evidence.period_digest_chain ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.period_digest_chain FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.period_digest_chain
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.period_digest_chain FROM evidence_runtime;
CREATE OR REPLACE FUNCTION evidence.period_digest_chain_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER period_digest_chain_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.period_digest_chain
    FOR EACH ROW EXECUTE FUNCTION evidence.period_digest_chain_immutable();
grant select, insert on evidence.period_digest_chain to evidence_runtime;
