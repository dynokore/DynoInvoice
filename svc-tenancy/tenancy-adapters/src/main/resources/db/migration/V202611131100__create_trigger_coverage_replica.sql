-- V202611131100: the tenant-isolated replica of ingestion.trigger_coverage (INV-REQ-788, INV-AC-20941). One row per
-- (tenant, entity, trigger class) is written by the TriggerCoverageChanged consumer (event-carried state transfer; the
-- handler replaces a row only for a newer changed_at). tenancy_runtime holds SELECT, INSERT and UPDATE only, no DELETE.
-- actor_user_id is the id of the acting user, never a role (INV-Q-10647). Forced RLS as the other tenancy tables.
-- Run by tenancy_migrate.
SET lock_timeout = '5s';

CREATE TABLE tenancy.trigger_coverage_replica (
    tenant_id          uuid        NOT NULL,
    entity_ref         text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 256 AND entity_ref = btrim(entity_ref)),
    trigger_class      text        NOT NULL CHECK (trigger_class IN ('OUTBOUND_DOCUMENT_ISSUED', 'INBOUND_DOCUMENT_RECORDED',
                                                                      'AGGREGABLE_BATCH_CLOSED', 'JOURNAL_ONLY_SUPPLY',
                                                                      'THIRD_PARTY_ISSUANCE')),
    status             text        NOT NULL CHECK (status IN ('MAPPED', 'WAIVED', 'COVERED_BY_OTHER_SOLUTION')),
    adapter_ref        text        CHECK (adapter_ref IS NULL OR length(adapter_ref) BETWEEN 1 AND 256),
    review_date        date,
    covering_solution  text        CHECK (covering_solution IS NULL OR length(covering_solution) BETWEEN 1 AND 512),
    evidence_reference text        CHECK (evidence_reference IS NULL OR length(evidence_reference) BETWEEN 1 AND 512),
    changed_at         timestamptz NOT NULL,
    actor_user_id      text        CHECK (actor_user_id IS NULL OR length(actor_user_id) BETWEEN 1 AND 128),
    PRIMARY KEY (tenant_id, entity_ref, trigger_class)
);

ALTER TABLE tenancy.trigger_coverage_replica ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.trigger_coverage_replica FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.trigger_coverage_replica
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.trigger_coverage_replica TO tenancy_runtime;
