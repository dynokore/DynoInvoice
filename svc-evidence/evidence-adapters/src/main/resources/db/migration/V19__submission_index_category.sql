-- V19__submission_index_category.sql (INV-T-20768; INV-REQ-724, slice E4e of the reverse lookup).
--
-- Category child rows of the read model: one row per distinct line tax code of a document version (coordinator ruling,
-- owner 2026-10-14, option A). The code is stored exactly as on the document, with no length limit, so a long code can
-- never fail the consumer transaction. A read model, not evidence: no immutability trigger; SELECT and INSERT only,
-- no UPDATE and no DELETE. Expand-only: nothing existing is altered.

create table evidence.submission_index_category (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null,
    category_code text not null,
    primary key (tenant_id, doc_uuid, version, category_code),
    foreign key (tenant_id, doc_uuid, version) references evidence.submission_index (tenant_id, doc_uuid, version)
);
create index submission_index_category_code on evidence.submission_index_category (tenant_id, category_code);
ALTER TABLE evidence.submission_index_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.submission_index_category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.submission_index_category
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE ALL ON evidence.submission_index_category FROM evidence_runtime;
grant select, insert on evidence.submission_index_category to evidence_runtime;
