-- V202611161000__document_party_ref.sql (INV-T-21359; INV-REQ-479, round zv ruling).
-- The counterparty reference the record step received from masterdata, once per tenant and document; the hold reads it.
CREATE TABLE canonical.document_party_ref (
    tenant_id uuid NOT NULL,
    doc_uuid uuid NOT NULL,
    party_ref text NOT NULL CHECK (length(party_ref) BETWEEN 1 AND 40),
    PRIMARY KEY (tenant_id, doc_uuid)
);
ALTER TABLE canonical.document_party_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.document_party_ref FORCE ROW LEVEL SECURITY;
CREATE POLICY document_party_ref_tenant_isolation ON canonical.document_party_ref
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON canonical.document_party_ref TO canonical_runtime;
