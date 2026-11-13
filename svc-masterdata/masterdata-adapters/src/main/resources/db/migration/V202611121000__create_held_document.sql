-- INV-T-21333 (INV-REQ-941; slice H2, TPL-11, TPL-12). The held-documents replica of masterdata-svc, fed by the canonical
-- DocumentHeld and DocumentReleased events. One row per tenant and document; a released document keeps its row as a
-- tombstone with its sequence, so a late lower-sequence event cannot re-create a hold (no DELETE is granted).

CREATE TABLE masterdata.held_document (
    tenant_id   uuid    NOT NULL,
    doc_uuid    uuid    NOT NULL,
    docsequence bigint  NOT NULL CHECK (docsequence >= 1),
    held        boolean NOT NULL,
    entity      text    CHECK (length(entity) BETWEEN 1 AND 40),
    party_ref   text    CHECK (length(party_ref) BETWEEN 1 AND 40),
    PRIMARY KEY (tenant_id, doc_uuid)
);

CREATE INDEX held_document_party ON masterdata.held_document (tenant_id, entity, party_ref) WHERE held;

ALTER TABLE masterdata.held_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.held_document FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.held_document
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.held_document TO masterdata_runtime;
