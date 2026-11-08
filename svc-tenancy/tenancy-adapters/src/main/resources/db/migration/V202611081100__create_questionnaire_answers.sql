-- V202611011100: the answers of the onboarding questionnaire, one record per tenant and questionnaire version
-- (INV-REQ-1246 b3; INV-AC-9593). Answering the same version again replaces that record, another version keeps both.
-- The answers are kept as submitted (question key to answer text, one JSON object); the service reads no meaning
-- into them.
-- Run by tenancy_migrate. Forced RLS on the signed binding (INV-REQ-854 b1).
SET lock_timeout = '5s';

CREATE TABLE tenancy.questionnaire_answers (
    id                    uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    questionnaire_version text        NOT NULL CHECK (length(questionnaire_version) BETWEEN 1 AND 16),
    answers               text        NOT NULL CHECK (jsonb_typeof(answers::jsonb) = 'object'),
    recorded_at           timestamptz NOT NULL,
    UNIQUE (tenant_id, questionnaire_version)
);

ALTER TABLE tenancy.questionnaire_answers ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.questionnaire_answers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.questionnaire_answers
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.questionnaire_answers TO tenancy_runtime;
