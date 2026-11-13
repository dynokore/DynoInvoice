-- V202611121000__create_trigger_coverage.sql (INV-T-20938; INV-REQ-788, INV-Q-9704 round d option B)
-- The trigger-class registry of ingestion-svc: one row per (tenant, entity, trigger class). The acting user is stored
-- by id in recorded_by, never a role (INV-Q-10647). Row-level security is forced with the signed-binding policy of
-- the other tables; the runtime role never deletes.
create table ingestion.trigger_coverage (
    tenant_id uuid not null,
    entity_ref text not null,
    trigger_class text not null check (trigger_class in (
        'OUTBOUND_DOCUMENT_ISSUED', 'INBOUND_DOCUMENT_RECORDED', 'AGGREGABLE_BATCH_CLOSED',
        'JOURNAL_ONLY_SUPPLY', 'THIRD_PARTY_ISSUANCE')),
    status text not null check (status in ('MAPPED', 'WAIVED', 'COVERED_BY_OTHER_SOLUTION')),
    adapter_ref text,
    review_date date,
    covering_solution text,
    evidence_reference text,
    recorded_by uuid not null,
    recorded_at timestamptz not null,
    primary key (tenant_id, entity_ref, trigger_class),
    check (
        (status = 'MAPPED' and adapter_ref is not null and review_date is null
            and covering_solution is null and evidence_reference is null)
        or (status = 'WAIVED' and review_date is not null and adapter_ref is null
            and covering_solution is null and evidence_reference is null)
        or (status = 'COVERED_BY_OTHER_SOLUTION' and covering_solution is not null
            and evidence_reference is not null and adapter_ref is null and review_date is null))
);
ALTER TABLE ingestion.trigger_coverage ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingestion.trigger_coverage FORCE ROW LEVEL SECURITY;
CREATE POLICY trigger_coverage_tenant_isolation ON ingestion.trigger_coverage
    USING      (tenant_id = (select ingestion.bound_tenant()))
    WITH CHECK (tenant_id = (select ingestion.bound_tenant()));
grant select, insert, update on ingestion.trigger_coverage to ingestion_runtime;
