-- INV-T-17443 (INV-REQ-899, INV-FLOW-255 step 1; CS-19, ARC-20, TPL-12). The client signatories masterdata-svc records
-- (recordClientSignatory) with the state of their consent link. Run only by the migration Job as masterdata_migrate.
-- link_state starts PENDING; request_event_id is the CloudEvents id of the SignedLinkRequested written in the same
-- transaction, and the SignedLinkOutcomeRecorded consumer finds the row by it. A repeat of (client_ref, signatory_email)
-- returns the existing row (unique index).

CREATE TABLE masterdata.client_signatory (
    tenant_id        uuid        NOT NULL,
    signatory_ref    text        NOT NULL CHECK (length(signatory_ref) BETWEEN 1 AND 64),
    client_ref       text        NOT NULL CHECK (length(client_ref) BETWEEN 1 AND 40),
    signatory_name   text        NOT NULL CHECK (length(signatory_name) BETWEEN 1 AND 200),
    signatory_email  text        NOT NULL CHECK (length(signatory_email) BETWEEN 1 AND 254),
    signatory_mobile text        CHECK (length(signatory_mobile) BETWEEN 1 AND 20),
    consent_ref      text        NOT NULL CHECK (length(consent_ref) BETWEEN 1 AND 64),
    link_state       text        NOT NULL CHECK (link_state IN ('PENDING', 'ISSUED', 'REFUSED')),
    link_reason_code text        CHECK (link_reason_code IN ('MOBILE_NUMBER_REQUIRED', 'CONSENT_NOT_LIVE', 'RECIPIENT_INVALID', 'EXPIRY_INVALID')),
    request_event_id text        CHECK (length(request_event_id) BETWEEN 1 AND 128),
    recorded_at      timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, signatory_ref),
    CHECK ((link_state = 'REFUSED') = (link_reason_code IS NOT NULL))
);

CREATE UNIQUE INDEX client_signatory_natural_key ON masterdata.client_signatory (tenant_id, client_ref, signatory_email);
CREATE UNIQUE INDEX client_signatory_request_event ON masterdata.client_signatory (tenant_id, request_event_id) WHERE request_event_id IS NOT NULL;

ALTER TABLE masterdata.client_signatory ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.client_signatory FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.client_signatory
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.client_signatory TO masterdata_runtime;
