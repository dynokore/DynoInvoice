-- V202611111000: the append-only history of every rendition attestation and state change (INV-REQ-1036 behavior 1,
-- INV-AC-20308). One row is added in the transaction that writes tenancy.rendition_attestation; a row is never changed
-- or removed (NIST SP 800-53 AU-9): tenancy_runtime holds SELECT and INSERT only, and a trigger refuses UPDATE, DELETE
-- and TRUNCATE for every role. Tenant-scoped with forced RLS on the signed binding (INV-REQ-854 b1). Run by tenancy_migrate.
SET lock_timeout = '5s';

CREATE TABLE tenancy.rendition_attestation_history (
    seq             bigint      GENERATED ALWAYS AS IDENTITY,
    tenant_id       uuid        NOT NULL,
    entity_id       uuid        NOT NULL,
    document_class  text        NOT NULL CHECK (length(document_class) BETWEEN 1 AND 64 AND document_class = btrim(document_class)),
    kind            text        NOT NULL CHECK (kind IN ('ATTESTED', 'STATE_CHANGED')),
    renderer        text        NOT NULL CHECK (renderer IN ('SELF', 'AGENT', 'AGGREGATOR')),
    attested_by     text        NOT NULL CHECK (length(attested_by) BETWEEN 1 AND 128),
    attested_at     timestamptz NOT NULL,
    state           text        NOT NULL CHECK (state IN ('CURRENT', 'REQUESTED_AGAIN')),
    recorded_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, entity_id, seq),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id)
);

ALTER TABLE tenancy.rendition_attestation_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.rendition_attestation_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.rendition_attestation_history
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

CREATE FUNCTION tenancy.rendition_attestation_history_append_only() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'tenancy.rendition_attestation_history is append-only: % refused', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER rendition_attestation_history_no_change
    BEFORE UPDATE OR DELETE ON tenancy.rendition_attestation_history
    FOR EACH ROW EXECUTE FUNCTION tenancy.rendition_attestation_history_append_only();
CREATE TRIGGER rendition_attestation_history_no_truncate
    BEFORE TRUNCATE ON tenancy.rendition_attestation_history
    FOR EACH STATEMENT EXECUTE FUNCTION tenancy.rendition_attestation_history_append_only();

GRANT SELECT, INSERT ON tenancy.rendition_attestation_history TO tenancy_runtime;
