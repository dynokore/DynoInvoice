-- INV-T-26224 (INV-REQ-316, INV-REQ-2445; slice 1). The held-documents replica keeps a last-writer-wins mark per
-- document and hold reason, so a release removes only the reasons it names and the replica converges whatever the
-- delivery order. identity_docsequence is the sequence of the hold that set entity and party_ref (null: no hold seen).

ALTER TABLE masterdata.held_document ADD COLUMN identity_docsequence bigint CHECK (identity_docsequence >= 1);

CREATE TABLE masterdata.held_document_reason (
    tenant_id   uuid    NOT NULL,
    doc_uuid    uuid    NOT NULL,
    reason      text    NOT NULL,
    docsequence bigint  NOT NULL CHECK (docsequence >= 1),
    held        boolean NOT NULL,
    PRIMARY KEY (tenant_id, doc_uuid, reason)
);

ALTER TABLE masterdata.held_document_reason ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.held_document_reason FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.held_document_reason
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.held_document_reason TO masterdata_runtime;
